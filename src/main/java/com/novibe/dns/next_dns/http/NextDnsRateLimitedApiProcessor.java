package com.novibe.dns.next_dns.http;

import com.novibe.common.exception.DnsHttpError;
import com.novibe.common.util.Log;
import com.novibe.dns.next_dns.http.dto.response.NextDnsResponse;
import lombok.experimental.UtilityClass;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

@UtilityClass
public class NextDnsRateLimitedApiProcessor {

    private static final int MAX_RETRIES = 5;

    public <D, R extends NextDnsResponse<?>> void callApi(List<D> requestList, Function<D, R> request) {
        for (int index = 0; index < requestList.size(); index++) {
            D requestDto = requestList.get(index);
            int retries = 0;
            while (true) {
                try {
                    R response = request.apply(requestDto);
                    if (response != null && response.getErrors() != null && !response.getErrors().isEmpty()) {
                        throw new IllegalStateException("NextDNS rejected request: " + response.getErrors());
                    }
                    Log.progress("Current success progress: " + (index + 1) + "/" + requestList.size());
                    break;
                } catch (DnsHttpError error) {
                    if ((error.getCode() != 429 && error.getCode() != 524) || retries >= MAX_RETRIES) {
                        throw error;
                    }
                    retries++;
                    long waitSeconds = Math.min(60, 5L << (retries - 1));
                    Log.common("NextDNS HTTP %s; retry %s/%s in %s seconds"
                            .formatted(error.getCode(), retries, MAX_RETRIES, waitSeconds));
                    sleep(waitSeconds);
                }
            }
        }
        Log.common("\nCompleted");
    }

    private void sleep(long seconds) {
        try {
            Thread.sleep(Duration.ofSeconds(seconds));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("NextDNS retry wait was interrupted", exception);
        }
    }
}
