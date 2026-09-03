package com.novibe.common.data_sources;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.base_structures.HostsLine;
import com.novibe.common.util.DataParser;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

@Service
public class HostsOverrideListsLoader extends ListLoader<BypassRoute> {

    @Override
    protected String listType() {
        return "Override";
    }

    @Override
    protected Predicate<HostsLine> filterRelatedLines() {
        return line -> line.hasIpAndDomain() && !HostsBlockListsLoader.isBlockIp(line.ip());

    }

    @Override
    protected BypassRoute toObject(HostsLine line) {
        return new BypassRoute(line.ip(), line.domain());
    }

    public PrioritizedOverrides fetchWebsitesWithPrioritySection(List<String> urls, String prioritySection) {
        List<String> lines = fetchLines(urls);
        List<BypassRoute> routes = parseWebsites(lines);
        Set<String> priorityDomains = domainsInSection(lines, prioritySection);
        return new PrioritizedOverrides(routes, priorityDomains);
    }

    static Set<String> domainsInSection(List<String> lines, String sectionName) {
        String expectedHeading = normalizeHeading(sectionName);
        Set<String> domains = new LinkedHashSet<>();
        boolean insideSection = false;

        for (String line : lines) {
            String stripped = line.strip();
            if (DataParser.isComment(stripped)) {
                insideSection = normalizeHeading(stripped).equals(expectedHeading);
                continue;
            }
            if (!insideSection) continue;

            HostsLine hostsLine = DataParser.parseHostsLine(stripped.toLowerCase(Locale.ROOT));
            if (hostsLine != null && hostsLine.hasIpAndDomain()
                    && !HostsBlockListsLoader.isBlockIp(hostsLine.ip())) {
                domains.add(hostsLine.domain());
            }
        }
        return Set.copyOf(domains);
    }

    private static String normalizeHeading(String value) {
        String heading = Objects.requireNonNullElse(value, "").strip();
        int firstText = 0;
        while (firstText < heading.length() && heading.charAt(firstText) == '#') firstText++;
        return heading.substring(firstText).strip().toLowerCase(Locale.ROOT);
    }

    public record PrioritizedOverrides(List<BypassRoute> routes, Set<String> priorityDomains) {
        public PrioritizedOverrides {
            routes = List.copyOf(routes);
            priorityDomains = Set.copyOf(priorityDomains);
        }

        @Override
        public List<BypassRoute> routes() {
            return List.copyOf(routes);
        }

        @Override
        public Set<String> priorityDomains() {
            return Set.copyOf(priorityDomains);
        }
    }
}
