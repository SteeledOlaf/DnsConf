package com.novibe.dns.next_dns.http;

import com.novibe.dns.next_dns.http.dto.response.NextDnsResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NextDnsRateLimitedApiProcessorTest {

    @Test
    void acceptsEmptyErrorsAndRejectsApiErrors() {
        assertDoesNotThrow(() -> NextDnsRateLimitedApiProcessor.callApi(List.of("ok"), ignored -> {
            NextDnsResponse<String> response = new NextDnsResponse<>();
            response.setErrors(List.of());
            return response;
        }));

        assertThrows(IllegalStateException.class, () ->
                NextDnsRateLimitedApiProcessor.callApi(List.of("bad"), ignored -> {
                    NextDnsResponse<String> response = new NextDnsResponse<>();
                    response.setErrors(List.of(new NextDnsResponse.NextDnsApiError("invalid")));
                    return response;
                })
        );
    }
}
