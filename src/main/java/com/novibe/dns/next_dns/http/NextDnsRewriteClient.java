package com.novibe.dns.next_dns.http;

import com.novibe.dns.next_dns.http.dto.request.CreateRewriteDto;
import com.novibe.dns.next_dns.http.dto.response.rewrite.MultiRewriteResponse;
import com.novibe.dns.next_dns.http.dto.response.rewrite.RewriteDto;
import com.novibe.dns.next_dns.http.dto.response.rewrite.SingleRewriteResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class NextDnsRewriteClient extends AbstractNextDnsHttpClient {

    public List<RewriteDto> fetchRewrites() {
        List<RewriteDto> result = new ArrayList<>();
        String cursor = null;
        do {
            String requestPath = path() + "?limit=500" + (cursor == null ? "" : "&cursor="
                    + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            MultiRewriteResponse response = get(requestPath, MultiRewriteResponse.class);
            requireNoErrors(response);
            if (response.getData() != null) result.addAll(response.getData());
            cursor = response.getMeta() == null || response.getMeta().pagination() == null
                    ? null : response.getMeta().pagination().cursor();
        } while (cursor != null && !cursor.isBlank());
        return List.copyOf(result);
    }

    public SingleRewriteResponse saveRewrite(CreateRewriteDto rewriteDto) {
        return post(path(), rewriteDto, SingleRewriteResponse.class);
    }

    public @Nullable SingleRewriteResponse deleteRewriteById(String id) {
        return delete(path() + "/" + id, SingleRewriteResponse.class);
    }

    @Override
    protected String path() {
        return "/rewrites";
    }

    private static void requireNoErrors(MultiRewriteResponse response) {
        if (response == null || (response.getErrors() != null && !response.getErrors().isEmpty())) {
            throw new IllegalStateException("Failed to fetch NextDNS rewrites: "
                    + (response == null ? "empty response" : response.getErrors()));
        }
    }

}
