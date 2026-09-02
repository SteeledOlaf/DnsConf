package com.novibe.dns.next_dns.http;

import com.novibe.common.exception.DnsHttpError;
import com.novibe.common.util.Log;
import com.novibe.dns.next_dns.http.dto.response.NextDnsResponse;
import lombok.experimental.UtilityClass;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Function;

@UtilityClass
public class NextDnsRateLimitedApiProcessor {

    public <D, R extends NextDnsResponse<?>> void callApi(List<D> requestList, Function<D, R> request) {
        int requestsUntilRateLimitHitCounter = 0;
        for (int i = 0; i < requestList.size(); i++) {
            D requestDto = requestList.get(i);
            try {
                R response = request.apply(requestDto);
                if (response != null && response.getErrors() != null && !response.getErrors().isEmpty()) {
                    Log.fail("Failed request: " + response.getErrors());
                } else {
                    Log.progress("Current success progress: " + (i + 1) + "/" + requestList.size());
                    requestsUntilRateLimitHitCounter++;
                }
            } catch (DnsHttpError e) {
                if (e.getCode() == 524 || e.getCode() == 429) {
                    Log.common("\nSending speed: %s requests per second"
                            .formatted((double) requestsUntilRateLimitHitCounter / 60));
                    Log.common("Code %s. Api rate limit has reached".formatted(e.getCode()));
                    i--;
                    runRateLimiterResetWaitTimer();
                    requestsUntilRateLimitHitCounter = 0;
                    Log.io("Continue...");
                } else {
                    throw e;
                }
            }
        }
        Log.common("\nCompleted");
    }

    private void runRateLimiterResetWaitTimer() {
        final int WAIT_SECONDS = 60;
        for (int timer = WAIT_SECONDS; timer > 0; timer--) {
            try {
                Thread.sleep(Duration.of(1, ChronoUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            Log.progress("Waiting for reset: " + timer + " seconds");
        }
    }

}
