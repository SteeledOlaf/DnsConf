package com.novibe.common.data_sources;

import com.novibe.common.base_structures.HostsLine;
import com.novibe.common.security.NetworkTargetPolicy;
import com.novibe.common.util.DataParser;
import com.novibe.common.util.Log;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Setter(onMethod_ = @Autowired)
public abstract class ListLoader<T> {

    private static final long MAX_LIST_BYTES = 50L * 1024 * 1024;
    private HttpClient client;

    protected abstract T toObject(HostsLine hostsLine);

    protected abstract String listType();

    protected abstract Predicate<HostsLine> filterRelatedLines();

    public List<T> fetchWebsites(List<String> urls) {
        if (urls.isEmpty()) return new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<String>>> requests = urls.stream()
                    .map(url -> executor.submit(() -> fetchList(url)))
                    .toList();
            List<String> lines = new ArrayList<>();
            for (Future<List<String>> request : requests) lines.addAll(request.get());
            return lines.stream()
                    .map(String::strip)
                    .filter(line -> !line.isBlank())
                    .filter(line -> !DataParser.isComment(line))
                    .map(String::toLowerCase)
                    .map(DataParser::parseHostsLine)
                    .filter(Objects::nonNull)
                    .filter(filterRelatedLines())
                    .distinct()
                    .map(this::toObject)
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("List loading was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Failed to load a configured list source", exception.getCause());
        }
    }

    private List<String> fetchList(String url) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        NetworkTargetPolicy.requirePublicHttps(uri);
        Log.io("Loading %s list from host: %s".formatted(listType(), uri.getHost()));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IOException("List source returned HTTP %s: %s".formatted(response.statusCode(), uri.getHost()));
        }
        NetworkTargetPolicy.requirePublicHttps(response.uri());
        try (InputStream body = new LimitedInputStream(response.body(), MAX_LIST_BYTES);
             BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long bytesRead;

        private LimitedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) addBytes(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, length);
            if (count > 0) addBytes(count);
            return count;
        }

        private void addBytes(int count) throws IOException {
            bytesRead += count;
            if (bytesRead > limit) throw new IOException("List source exceeds 50 MiB limit");
        }
    }
}
