package com.novibe.common.data_sources;

import com.novibe.common.config.AppSettings;
import com.novibe.common.util.DataParser;
import com.novibe.common.util.EnvParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ExcludeRedirectSettingsLoader {

    private final AppSettings settings;

    public List<String> loadIgnoredDomains() {
        return Optional.ofNullable(EnvParser.parse(settings.excludeRedirect()))
                .stream()
                .flatMap(List::stream)
                .map(String::trim)
                .map(String::toLowerCase)
                .map(DataParser::removeWWW)
                .distinct()
                .toList();
    }
}
