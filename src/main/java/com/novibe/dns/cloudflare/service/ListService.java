package com.novibe.dns.cloudflare.service;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.util.Log;
import com.novibe.dns.cloudflare.http.CloudflareListClient;
import com.novibe.dns.cloudflare.http.dto.request.CreateListRequest;
import com.novibe.dns.cloudflare.http.dto.response.list.GatewayListDto;
import com.novibe.dns.cloudflare.http.dto.response.list.SingleListApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ListService {

    private final CloudflareListClient cloudflareListClient;
    private final CloudflareListPlanner planner;
    private final OwnershipMarker ownershipMarker;

    public List<GatewayListDto> createNewBlockLists(List<String> normalizedDomains) {
        Log.common("Total websites count: " + normalizedDomains.size());
        return saveNewLists(planner.blockRequests(normalizedDomains));
    }

    public Map<String, List<GatewayListDto>> createNewOverrideLists(List<BypassRoute> normalizedRoutes) {
        Map<String, List<GatewayListDto>> result = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, List<CreateListRequest>> entry : planner.redirectRequests(normalizedRoutes).entrySet()) {
                Log.io("Posting %s override lists for IP: %s".formatted(entry.getValue().size(), entry.getKey()));
                result.put(entry.getKey(), saveNewLists(entry.getValue()));
            }
            return result;
        } catch (RuntimeException creationFailure) {
            try {
                removeLists(result.values().stream().flatMap(Collection::stream).toList());
            } catch (RuntimeException rollbackFailure) {
                creationFailure.addSuppressed(rollbackFailure);
            }
            throw creationFailure;
        }
    }

    public List<GatewayListDto> obtainManagedLists() {
        return cloudflareListClient.getLists().stream()
                .filter(list -> planner.isManagedName(list.getName()))
                .filter(list -> ownershipMarker.owns(list.getDescription())
                        || ownershipMarker.isLegacySession(list.getDescription()))
                .toList();
    }

    public void removeLists(Collection<GatewayListDto> lists) {
        if (lists.isEmpty()) return;
        List<String> errors = new ArrayList<>();
        int removed = 0;
        for (GatewayListDto list : lists) {
            try {
                SingleListApiResponse response = cloudflareListClient.deleteListById(list.getId());
                if (response == null || !response.isSuccess()) {
                    errors.add(list.getId() + ": " + (response == null ? "empty response" : response.getErrors()));
                } else {
                    Log.progress(++removed + "/" + lists.size() + " removed");
                }
            } catch (RuntimeException exception) {
                errors.add(list.getId() + ": " + exception.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Failed to remove Cloudflare Gateway lists: " + errors);
        }
    }

    private List<GatewayListDto> saveNewLists(List<CreateListRequest> requests) {
        List<GatewayListDto> created = new ArrayList<>();
        try {
            for (CreateListRequest request : requests) {
                SingleListApiResponse response = cloudflareListClient.postList(request);
                if (response == null || !response.isSuccess() || response.getResult() == null) {
                    throw new IllegalStateException("Cloudflare rejected Gateway list: "
                            + (response == null ? "empty response" : response.getErrors()));
                }
                created.add(response.getResult());
                Log.progress(created.size() + "/" + requests.size() + " saved");
            }
            return List.copyOf(created);
        } catch (RuntimeException creationFailure) {
            try {
                removeLists(created);
            } catch (RuntimeException rollbackFailure) {
                creationFailure.addSuppressed(rollbackFailure);
            }
            throw creationFailure;
        }
    }
}
