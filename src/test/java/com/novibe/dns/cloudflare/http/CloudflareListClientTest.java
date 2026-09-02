package com.novibe.dns.cloudflare.http;

import com.novibe.dns.cloudflare.http.dto.response.ResultInfo;
import com.novibe.dns.cloudflare.http.dto.response.list.GatewayListDto;
import com.novibe.dns.cloudflare.http.dto.response.list.MultiListApiResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudflareListClientTest {

    @Test
    void followsAllResultPages() {
        FakeRequest request = new FakeRequest();
        request.responses.add(page(3, UUID.randomUUID(), UUID.randomUUID()));
        request.responses.add(page(3, UUID.randomUUID()));

        List<GatewayListDto> lists = new CloudflareListClient(request).getLists();

        assertEquals(3, lists.size());
        assertEquals(2, request.calls);
    }

    private static MultiListApiResponse page(int total, UUID... ids) {
        MultiListApiResponse response = new MultiListApiResponse();
        response.setSuccess(true);
        response.setResult(java.util.Arrays.stream(ids).map(id -> {
            GatewayListDto dto = new GatewayListDto();
            dto.setId(id);
            return dto;
        }).toList());
        ResultInfo info = new ResultInfo();
        info.setTotalCount(total);
        response.setResultInfo(info);
        return response;
    }

    private static final class FakeRequest extends RequestCloudflare {
        private final Queue<MultiListApiResponse> responses = new ArrayDeque<>();
        private int calls;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(String path, Class<T> responseType) {
            calls++;
            return (T) responses.remove();
        }
    }
}
