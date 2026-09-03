package com.novibe.dns.cloudflare.http.dto.response.rule;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class GatewayRuleDto {
    @EqualsAndHashCode.Include
    String id;
    String name;
    String description;
    String action;
    @SerializedName("created_at")
    String createdAt;
    String traffic;
    @SerializedName("rule_settings")
    GatewayRuleSettingsDto ruleSettings;
    int precedence;
    boolean enabled;

    @Data
    public static class GatewayRuleSettingsDto {
        @SerializedName("override_ips")
        List<String> overrideIps;
    }
}
