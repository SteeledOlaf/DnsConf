package com.novibe.dns.cloudflare.http.dto.request;

import com.novibe.common.util.Jsonable;
import com.novibe.dns.cloudflare.http.dto.Item;
import lombok.Builder;

import java.util.List;

@Builder
public record CreateListRequest(String name, String type, String description, List<Item> items) implements Jsonable {

    public CreateListRequest {
        items = List.copyOf(items);
    }

    @Override
    public List<Item> items() {
        return List.copyOf(items);
    }
}
