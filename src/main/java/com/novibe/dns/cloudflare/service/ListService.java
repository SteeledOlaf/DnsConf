package com.novibe.dns.cloudflare.service;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.service.ExcludeRedirectCheckService;
import com.novibe.common.util.FunctionWrapper;
import com.novibe.common.util.Log;
import com.novibe.dns.cloudflare.http.CloudflareListClient;
import com.novibe.dns.cloudflare.http.dto.Item;
import com.novibe.dns.cloudflare.http.dto.request.CreateListRequest;
import com.novibe.dns.cloudflare.http.dto.response.CloudflareApiMessage;
import com.novibe.dns.cloudflare.http.dto.response.list.GatewayListDto;
import com.novibe.dns.cloudflare.http.dto.response.list.SingleListApiResponse;
import lombok.Cleanup;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ListService {

    private static final String BLOCK_LIST_NAME_PREFIX = "Blocked websites by script";
    private static final String OVERRIDE_LIST_NAME_PREFIX = "Override websites by script";

    /**
     * Cloudflare Zero Trust Gateway DOMAIN lists
     * не поддерживают wildcard вида *.example.com.
     *
     * Поэтому такие записи перед отправкой превращаются
     * в example.com.
     */
    private static final Pattern DOMAIN_PATTERN = Pattern.compile(
            "^(?=.{1,253}$)(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$"
    );

    private static final int CHUNK_SIZE = 1000;

    private final CloudflareListClient cloudflareListClient;
    private final ExcludeRedirectCheckService excludeRedirectCheckService;
    private final String sessionId;

    public List<GatewayListDto> createNewBlockLists(List<String> websitesToBlock) {

        List<String> normalizedWebsites = normalizeWebsites(websitesToBlock);

        List<List<Item>> websitesByChunks = cutChunks(websiteAsItem(normalizedWebsites));

        Log.common(
                "Total websites count: %s\nPrepared %s chunks of websites list to block."
                        .formatted(normalizedWebsites.size(), websitesByChunks.size())
        );

        List<CreateListRequest> createListRequests =
                mapToBlockListRequests(websitesByChunks);

        return saveNewLists(createListRequests);
    }

    public void omitExcludedOverrides(List<BypassRoute> routes) {
        routes.removeIf(route -> excludeRedirectCheckService.shouldExclude(route.website()));
    }

    public Map<String, List<GatewayListDto>> createNewOverrideLists(
            List<BypassRoute> routes
    ) {

        Map<String, List<GatewayListDto>> result = new HashMap<>();

        Map<String, List<CreateListRequest>> requests =
                formOverrideListRequestsByIp(routes);

        for (Map.Entry<String, List<CreateListRequest>> entry : requests.entrySet()) {

            String overrideIp = entry.getKey();

            Log.io(
                    "Posting %s override lists for IP: %s"
                            .formatted(entry.getValue().size(), overrideIp)
            );

            List<GatewayListDto> response = saveNewLists(entry.getValue());

            result.put(overrideIp, response);
        }

        return result;
    }

    public void removeOldLists() {

        List<UUID> oldIds = cloudflareListClient.getLists()
                .stream()
                .filter(list ->
                        list.getName().startsWith(BLOCK_LIST_NAME_PREFIX)
                                || list.getName().startsWith(OVERRIDE_LIST_NAME_PREFIX)
                )
                .filter(list -> !sessionId.equals(list.getDescription()))
                .map(GatewayListDto::getId)
                .toList();

        if (oldIds.isEmpty()) {
            Log.common("No lists found to remove");
            return;
        }

        Log.io("Removing " + oldIds.size() + " lists...");

        AtomicInteger counter = new AtomicInteger();

        @Cleanup ExecutorService executor =
                Executors.newVirtualThreadPerTaskExecutor();

        List<List<CloudflareApiMessage>> errors = oldIds.stream()
                .map(id ->
                        executor.submit(
                                () -> cloudflareListClient.deleteListById(id)
                        )
                )
                .map(FunctionWrapper.wrap(Future::get))
                .peek(response -> {
                    if (response.isSuccess()) {
                        Log.progress(
                                counter.incrementAndGet()
                                        + "/" + oldIds.size()
                                        + " removed"
                        );
                    }
                })
                .filter(response -> !response.isSuccess())
                .map(SingleListApiResponse::getErrors)
                .toList();

        if (!errors.isEmpty()) {

            Log.fail(
                    "Failed to remove old lists (%s of %s): %s"
                            .formatted(errors.size(), oldIds.size(), errors)
            );

        } else {

            Log.common(
                    "\n%s of %s old lists have been removed"
                            .formatted(counter.get(), oldIds.size())
            );
        }
    }

    private List<GatewayListDto> saveNewLists(
            List<CreateListRequest> createListRequests
    ) {

        if (createListRequests.isEmpty()) {
            return List.of();
        }

        try (ExecutorService executor =
                     Executors.newVirtualThreadPerTaskExecutor()) {

            List<Future<SingleListApiResponse>> futures =
                    createListRequests.stream()
                            .map(list ->
                                    executor.submit(
                                            () -> cloudflareListClient.postList(list)
                                    )
                            )
                            .toList();

            List<List<CloudflareApiMessage>> errors = new ArrayList<>();
            List<GatewayListDto> result = new ArrayList<>();

            int counter = 0;

            for (Future<SingleListApiResponse> res : futures) {

                SingleListApiResponse response = res.get();

                if (response.isSuccess()) {

                    Log.progress(
                            ++counter
                                    + "/" + createListRequests.size()
                                    + " saved"
                    );

                    if (response.getResult() != null) {
                        result.add(response.getResult());
                    }

                } else {

                    errors.add(response.getErrors());
                }
            }

            if (!errors.isEmpty()) {

                Log.fail(
                        "Failed to save new lists (%s of %s): %s"
                                .formatted(
                                        errors.size(),
                                        createListRequests.size(),
                                        errors
                                )
                );

                throw new IllegalStateException(
                        "Cloudflare rejected one or more Gateway lists: "
                                + errors
                );
            }

            return result;

        } catch (ExecutionException e) {

            throw new RuntimeException(
                    "Failed to create Cloudflare Gateway lists",
                    e.getCause()
            );

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new RuntimeException(
                    "Creating Cloudflare Gateway lists was interrupted",
                    e
            );
        }
    }

    Map<String, List<CreateListRequest>> formOverrideListRequestsByIp(
            List<BypassRoute> routes
    ) {

        Map<String, String> mergedWebsiteOnIp = new HashMap<>();

        // Priority of IP is provided by source order.
        for (BypassRoute route : routes) {

            String normalizedWebsite =
                    normalizeDomain(route.website());

            if (normalizedWebsite != null) {

                mergedWebsiteOnIp.putIfAbsent(
                        normalizedWebsite,
                        route.ip()
                );
            }
        }

        // Group to lists by IP.
        Map<String, List<CreateListRequest>> result = new HashMap<>();

        Map<String, List<String>> ipForWebsites =
                mergedWebsiteOnIp.entrySet()
                        .stream()
                        .collect(
                                Collectors.groupingBy(
                                        Map.Entry::getValue,
                                        Collectors.mapping(
                                                Map.Entry::getKey,
                                                Collectors.toList()
                                        )
                                )
                        );

        for (Map.Entry<String, List<String>> entry :
                ipForWebsites.entrySet()) {

            List<List<Item>> chunks =
                    cutChunks(websiteAsItem(entry.getValue()));

            List<CreateListRequest> createListRequests =
                    mapToOverrideListRequests(
                            chunks,
                            entry.getKey()
                    );

            result.put(
                    entry.getKey(),
                    createListRequests
            );
        }

        return result;
    }

    private List<CreateListRequest> mapToBlockListRequests(
            List<List<Item>> chunkedWebsitesList
    ) {

        return mapToListRequests(
                chunkedWebsitesList,
                BLOCK_LIST_NAME_PREFIX
        );
    }

    private List<CreateListRequest> mapToOverrideListRequests(
            List<List<Item>> chunkedWebsitesList,
            String ip
    ) {

        return mapToListRequests(
                chunkedWebsitesList,
                OVERRIDE_LIST_NAME_PREFIX + " to IP " + ip
        );
    }

    private List<CreateListRequest> mapToListRequests(
            List<List<Item>> chunkedWebsitesList,
            String namePrefix
    ) {

        int chunkNumber = 1;

        ArrayList<CreateListRequest> requests =
                new ArrayList<>();

        for (List<Item> items : chunkedWebsitesList) {

            CreateListRequest newListRequestDto =
                    CreateListRequest.builder()
                            .name(
                                    namePrefix
                                            + " "
                                            + chunkNumber++
                            )
                            .type("DOMAIN")
                            .items(items)
                            .description(sessionId)
                            .build();

            requests.add(newListRequestDto);
        }

        return requests;
    }

    /**
     * Преобразование исходных строк в Item.
     */
    private List<Item> websiteAsItem(
            List<String> urlsToBlock
    ) {

        return urlsToBlock.stream()
                .map(this::normalizeDomain)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .map(Item::new)
                .toList();
    }

    /**
     * Нормализация домена перед отправкой в Cloudflare.
     *
     * Примеры:
     *
     * *.example.com
     *      -> example.com
     *
     * https://example.com/
     *      -> example.com
     *
     * https://example.com/path
     *      -> example.com
     *
     * example.com.
     *      -> example.com
     */
    private String normalizeDomain(String value) {

        if (value == null) {
            return null;
        }

        String domain = value.trim();

        if (domain.isEmpty()) {
            return null;
        }

        // Убираем комментарии.
        int commentIndex = domain.indexOf('#');

        if (commentIndex >= 0) {
            domain = domain.substring(0, commentIndex).trim();
        }

        if (domain.isEmpty()) {
            return null;
        }

        // Убираем URL scheme.
        domain = domain.replaceFirst(
                "^https?://",
                ""
        );

        // Убираем wildcard.
        while (domain.startsWith("*.")) {
            domain = domain.substring(2);
        }

        // Убираем путь.
        int slashIndex = domain.indexOf('/');

        if (slashIndex >= 0) {
            domain = domain.substring(0, slashIndex);
        }

        // Убираем query string.
        int questionMarkIndex = domain.indexOf('?');

        if (questionMarkIndex >= 0) {
            domain = domain.substring(0, questionMarkIndex);
        }

        // Убираем fragment.
        int hashIndex = domain.indexOf('#');

        if (hashIndex >= 0) {
            domain = domain.substring(0, hashIndex);
        }

        // Убираем порт.
        int colonIndex = domain.indexOf(':');

        if (colonIndex >= 0) {
            domain = domain.substring(0, colonIndex);
        }

        // Убираем конечную точку.
        while (domain.endsWith(".")) {
            domain = domain.substring(
                    0,
                    domain.length() - 1
            );
        }

        domain = domain.toLowerCase();

        if (domain.isEmpty()) {
            return null;
        }

        // Cloudflare DOMAIN list должна содержать hostname/domain,
        // а не IP-адрес.
        if (domain.matches(
                "^\\d{1,3}(?:\\.\\d{1,3}){3}$"
        )) {
            return null;
        }

        // Проверка доменного имени.
        if (!DOMAIN_PATTERN.matcher(domain).matches()) {

            Log.fail(
                    "Skipping invalid domain: " + value
            );

            return null;
        }

        return domain;
    }

    /**
     * Разбивает список на чанки фиксированного размера.
     */
    private static <T> List<List<T>> cutChunks(
            List<T> list
    ) {

        if (list.isEmpty()) {
            return List.of();
        }

        List<List<T>> chunks = new ArrayList<>();

        for (
                int start = 0;
                start < list.size();
                start += CHUNK_SIZE
        ) {

            int end = Math.min(
                    start + CHUNK_SIZE,
                    list.size()
            );

            chunks.add(
                    List.copyOf(
                            list.subList(start, end)
                    )
            );
        }

        return chunks;
    }

    /**
     * Нормализует и удаляет дубликаты из списка.
     */
    private List<String> normalizeWebsites(
            List<String> websites
    ) {

        if (websites == null || websites.isEmpty()) {
            return List.of();
        }

        Set<String> uniqueDomains =
                new LinkedHashSet<>();

        for (String website : websites) {

            String normalized =
                    normalizeDomain(website);

            if (normalized != null) {
                uniqueDomains.add(normalized);
            }
        }

        return new ArrayList<>(uniqueDomains);
    }
}
