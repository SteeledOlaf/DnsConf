package com.novibe.common.data_sources;

import com.novibe.common.base_structures.HostsLine;
import com.novibe.common.util.DataParser;
import com.novibe.common.util.Log;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Setter(onMethod_ = @Autowired)
public abstract class ListLoader<T> {

    private static final int MAX_LIST_BYTES = 50 * 1024 * 1024;

    private HttpClient client;

    protected abstract T toObject(HostsLine hostsLine);

    protected abstract String listType();

    protected abstract Predicate<HostsLine> filterRelatedLines();

    @SuppressWarnings("preview")
    public List<T> fetchWebsites(List<String> urls) {
        try (var scope = StructuredTaskScope.open()) {
            List<StructuredTaskScope.Subtask<String>> requests = new ArrayList<>();
            urls.stream()
                    .map(url -> scope.fork(() -> fetchList(url)))
                    .forEach(requests::add);
            scope.join();

            return requests.stream()
                    .map(StructuredTaskScope.Subtask::get)
                    .flatMap(DataParser::splitByEol)
                    .map(String::strip)
                    .parallel()
                    .filter(line -> !line.isBlank())
                    .filter(line -> !DataParser.isComment(line))
                    .map(String::toLowerCase)
                    .map(DataParser::parseHostsLine)
                    .filter(Objects::nonNull)
                    .filter(filterRelatedLines())
                    .distinct()
                    .map(this::toObject)
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private String fetchList(String url) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("Only HTTPS list sources are allowed: " + uri.getHost());
        }

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
        if (!"https".equalsIgnoreCase(response.uri().getScheme())) {
            response.body().close();
            throw new IOException("List source redirected outside HTTPS: " + uri.getHost());
        }

        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(MAX_LIST_BYTES + 1);
            if (bytes.length > MAX_LIST_BYTES) {
                throw new IOException("List source exceeds 50 MiB limit: " + uri.getHost());
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

}
