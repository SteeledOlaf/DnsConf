package com.novibe.dns.cloudflare.http;

import com.novibe.dns.cloudflare.http.dto.request.CreateListRequest;
import com.novibe.dns.cloudflare.http.dto.response.list.GatewayListDto;
import com.novibe.dns.cloudflare.http.dto.response.list.MultiListApiResponse;
import com.novibe.dns.cloudflare.http.dto.response.list.SingleListApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CloudflareListClient {

    private static final String PATH = "/lists";
    private final RequestCloudflare requestCloudflare;

    public List<GatewayListDto> getLists() {
        List<GatewayListDto> lists = new ArrayList<>();
        for (int page = 1; ; page++) {
            MultiListApiResponse response = requestCloudflare.get(
                    PATH + "?page=" + page + "&per_page=50", MultiListApiResponse.class
            );
            if (response == null || !response.isSuccess()) {
                throw new IllegalStateException("Failed to list Cloudflare Gateway lists: "
                        + (response == null ? "empty response" : response.getErrors()));
            }
            List<GatewayListDto> current = Objects.requireNonNullElse(response.getResult(), List.of());
            lists.addAll(current);
            if (response.getResultInfo() == null || current.isEmpty()) {
                return List.copyOf(lists);
            }
            int total = response.getResultInfo().getTotalCount();
            if (total > 0) {
                if (lists.size() >= total) return List.copyOf(lists);
            } else if (response.getResultInfo().getPerPage() <= 0
                    || current.size() < response.getResultInfo().getPerPage()) {
                return List.copyOf(lists);
            }
        }
    }

    public SingleListApiResponse postList(CreateListRequest request) {
        return requestCloudflare.post(PATH, request, SingleListApiResponse.class);
    }

    public SingleListApiResponse deleteListById(UUID listId) {
        return requestCloudflare.delete(PATH + "/" + listId, SingleListApiResponse.class);
    }
}
