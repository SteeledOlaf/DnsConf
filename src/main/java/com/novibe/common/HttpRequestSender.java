package com.novibe.common;

import com.google.gson.Gson;
import com.novibe.common.base_structures.DnsProfile;
import com.novibe.common.exception.DnsHttpError;
import com.novibe.common.util.Jsonable;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Semaphore;

import static java.util.Objects.isNull;

@Setter(onMethod_ = @Autowired)
public abstract class HttpRequestSender {

    /**
     * Ограничиваем количество одновременных запросов к Cloudflare API.
     *
     * 100 было слишком агрессивно для Gateway API.
     */
    private final Semaphore semaphore = new Semaphore(5);

    protected static final String GET = "GET";
    protected static final String POST = "POST";
    protected static final String DELETE = "DELETE";
    protected static final String PUT = "PUT";

    /**
     * Максимальное количество повторных попыток после HTTP 429.
     */
    private static final int MAX_RATE_LIMIT_RETRIES = 5;

    /**
     * Если Retry-After отсутствует или имеет некорректное значение,
     * используем exponential backoff:
     *
     * 5 -> 10 -> 20 -> 40 -> 60 секунд
     */
    private static final long INITIAL_BACKOFF_SECONDS = 5;

    private static final long MAX_BACKOFF_SECONDS = 60;

    protected abstract String apiUrl();

    protected abstract String authHeaderName();

    protected abstract String authHeaderValue();

    protected abstract void react401();

    protected abstract void react403();

    protected abstract void react404(DnsHttpError dnsHttpError);

    protected HttpClient httpClient;
    protected Gson jsonMapper;
    protected DnsProfile dnsProfile;

    public <T> T get(String path, Class<T> responseType) {
        return sendRequest(GET, path, null, responseType);
    }

    public <T, R extends Jsonable> T post(
            String path,
            R requestBody,
            Class<T> responseType
    ) {
        return sendRequest(POST, path, requestBody, responseType);
    }

    public <T> T delete(String path, Class<T> responseType) {
        return sendRequest(DELETE, path, null, responseType);
    }

    public <T, R extends Jsonable> T put(String path, R requestBody, Class<T> responseType) {
        return sendRequest(PUT, path, requestBody, responseType);
    }

    protected <T, R extends Jsonable> T sendRequest(
            String method,
            String path,
            R body,
            Class<T> responseBody
    ) {

        URI uri = URI.create(
                apiUrl() + (isNull(path) ? "" : path)
        );

        HttpRequest.BodyPublisher requestBody;

        if (isNull(body)) {
            requestBody = HttpRequest.BodyPublishers.noBody();
        } else {
            requestBody = HttpRequest.BodyPublishers.ofString(
                    body.toJson()
            );
        }

        int rateLimitAttempt = 0;

        while (true) {

            try {

                semaphore.acquire();

                try {

                    HttpRequest request =
                            HttpRequest.newBuilder(uri)
                                    .timeout(Duration.ofSeconds(30))
                                    .header(
                                            authHeaderName(),
                                            authHeaderValue()
                                    )
                                    .header(
                                            "Content-Type",
                                            "application/json"
                                    )
                                    .method(
                                            method,
                                            requestBody
                                    )
                                    .build();

                    HttpResponse<String> response =
                            httpClient.send(
                                    request,
                                    HttpResponse.BodyHandlers.ofString()
                            );

                    int statusCode = response.statusCode();

                    /*
                     * -------------------------------------------------
                     * HTTP 429 - Rate limit
                     * -------------------------------------------------
                     */
                    if (statusCode == 429) {

                        if (rateLimitAttempt >= MAX_RATE_LIMIT_RETRIES) {

                            throw new DnsHttpError(
                                    response,
                                    body
                            );
                        }

                        rateLimitAttempt++;

                        long waitSeconds =
                                getRetryDelaySeconds(
                                        response,
                                        rateLimitAttempt
                                );

                        System.out.printf(
                                "[Cloudflare] HTTP 429 rate limit. " +
                                "Retry %d/%d in %d seconds...%n",
                                rateLimitAttempt,
                                MAX_RATE_LIMIT_RETRIES,
                                waitSeconds
                        );

                        sleepSeconds(waitSeconds);

                        continue;
                    }

                    /*
                     * -------------------------------------------------
                     * Other HTTP errors
                     * -------------------------------------------------
                     */
                    if (statusCode > 299) {

                        DnsHttpError httpError =
                                new DnsHttpError(
                                        response,
                                        body
                                );

                        switch (statusCode) {

                            case 401 -> react401();

                            case 403 -> react403();

                            case 404 -> react404(httpError);

                            default -> throw httpError;
                        }
                    }

                    if (response.body().isEmpty()) {
                        return null;
                    }

                    return jsonMapper.fromJson(
                            response.body(),
                            responseBody
                    );

                } finally {

                    semaphore.release();
                }

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                throw new RuntimeException(
                        "HTTP request was interrupted",
                        e
                );

            } catch (IOException e) {

                throw new RuntimeException(
                        "HTTP request failed",
                        e
                );
            }
        }
    }

    /**
     * Получает количество секунд до следующей попытки.
     *
     * Сначала используется официальный Retry-After,
     * который Cloudflare возвращает при 429.
     *
     * Если его нет — используется exponential backoff.
     */
    private long getRetryDelaySeconds(
            HttpResponse<String> response,
            int attempt
    ) {

        String retryAfter =
                response.headers()
                        .firstValue("Retry-After")
                        .orElse(null);

        if (retryAfter != null) {

            try {

                long seconds =
                        Long.parseLong(
                                retryAfter.trim()
                        );

                return Math.max(
                        1,
                        Math.min(
                                seconds,
                                MAX_BACKOFF_SECONDS
                        )
                );

            } catch (NumberFormatException ignored) {
                // Переходим к exponential backoff.
            }
        }

        long backoff =
                INITIAL_BACKOFF_SECONDS
                        * (1L << Math.min(
                                attempt - 1,
                                4
                        ));

        return Math.min(
                backoff,
                MAX_BACKOFF_SECONDS
        );
    }

    private void sleepSeconds(long seconds) {

        try {

            Thread.sleep(
                    seconds * 1000L
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new RuntimeException(
                    "Rate-limit retry was interrupted",
                    e
            );
        }
    }
}
