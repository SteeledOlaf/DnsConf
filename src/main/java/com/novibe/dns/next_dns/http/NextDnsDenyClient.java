package com.novibe.dns.next_dns.http;

import com.novibe.dns.next_dns.http.dto.request.CreateDenyDto;
import com.novibe.dns.next_dns.http.dto.response.deny.DenyDto;
import com.novibe.dns.next_dns.http.dto.response.deny.MultiDenyResponse;
import com.novibe.dns.next_dns.http.dto.response.deny.SingleDenyResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
@RequiredArgsConstructor
public class NextDnsDenyClient extends AbstractNextDnsHttpClient {

    public List<DenyDto> fetchDenylist() {
        List<DenyDto> result = new ArrayList<>();
        String cursor = null;
        do {
            String requestPath = path() + "?limit=500" + (cursor == null ? "" : "&cursor="
                    + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
            MultiDenyResponse response = get(requestPath, MultiDenyResponse.class);
            requireNoErrors(response);
            if (response.getData() != null) result.addAll(response.getData());
            cursor = response.getMeta() == null || response.getMeta().pagination() == null
                    ? null : response.getMeta().pagination().cursor();
        } while (cursor != null && !cursor.isBlank());
        return List.copyOf(result);
    }

    public SingleDenyResponse saveDeny(CreateDenyDto rewriteDto) {
        return post(path(), rewriteDto, SingleDenyResponse.class);
    }


    public SingleDenyResponse deleteDenyById(String id) {
        return delete(path() + "/" + id, SingleDenyResponse.class);
    }

    @Override
    protected String path() {
        return "/denylist";
    }

    private static void requireNoErrors(MultiDenyResponse response) {
        if (response == null || (response.getErrors() != null && !response.getErrors().isEmpty())) {
            throw new IllegalStateException("Failed to fetch NextDNS denylist: "
                    + (response == null ? "empty response" : response.getErrors()));
        }
    }

}
