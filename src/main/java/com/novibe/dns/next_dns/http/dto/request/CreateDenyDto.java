package com.novibe.dns.next_dns.http.dto.request;

import com.novibe.common.util.Jsonable;

public record CreateDenyDto(String id, boolean active) implements Jsonable {

    public CreateDenyDto(String id) {
        this(id, true);
    }
}
