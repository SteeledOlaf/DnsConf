package com.novibe.dns.cloudflare.http.dto;

import com.google.gson.annotations.SerializedName;

public record Item(String value,
                   String description,
                   @SerializedName("created_at") String createdAt) {

    public Item(String value) {
        this(value, null, null);
    }
}
