package com.novibe.dns.cloudflare.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleServiceTest {

    @Test
    void buildsInlineDomainExpressionForRedirects() {
        List<String> expressions = RuleService.makeInlineTrafficExpressions(List.of(
                "example.com", "sub.example.net"
        ));

        assertEquals(1, expressions.size());
        assertEquals(
                "any(dns.domains[*] == \"example.com\") or any(dns.domains[*] == \"sub.example.net\")",
                expressions.getFirst()
        );
    }

    @Test
    void splitsVeryLargeInlineExpressionsBelowCloudflareLimit() {
        List<String> domains = java.util.stream.IntStream.range(0, 4_000)
                .mapToObj(index -> "domain-" + index + ".example.com")
                .toList();

        List<String> expressions = RuleService.makeInlineTrafficExpressions(domains);

        assertTrue(expressions.size() > 1);
        assertTrue(expressions.stream().allMatch(expression -> expression.length() <= 120_000));
    }
}
