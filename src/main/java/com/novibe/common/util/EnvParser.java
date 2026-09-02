package com.novibe.common.util;

import com.novibe.common.base_structures.DnsProfile;
import com.novibe.common.config.AppSettings;
import com.novibe.common.exception.UserInputException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;

import static java.util.Objects.isNull;

public class EnvParser {

    public static List<String> parse(String envValue) {
        if (isNull(envValue)) return new ArrayList<>();
        envValue = envValue.strip();
        if (envValue.isEmpty()) return new ArrayList<>();
        return Arrays.stream(envValue.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    public static List<DnsProfile> parseProfiles(AppSettings settings) {
        List<String> dnsList = parse(settings.dns());
        List<String> clientIdList = parse(settings.clientId());
        List<String> secretList = parse(settings.authSecret());
        List<String> donorList = parse(settings.donorDns());

        if (clientIdList.size() != secretList.size()) {
            throw UserInputException.noStackTrace("CLIENT_ID values amount and AUTH_SECRET values amount must be equal, but were %s and %s"
                    .formatted(clientIdList.size(), secretList.size()));
        }
        int profilesAmount = clientIdList.size();

        if (dnsList.size() == 1) {
            dnsList = Collections.nCopies(profilesAmount, dnsList.getFirst());
        } else if (dnsList.size() != profilesAmount) {
            throw UserInputException.noStackTrace("DNS values amount must be equal to CLIENT_ID values amount or contain exactly one provider");
        }

        donorList.replaceAll(val -> "-".equals(val) ? null : val);
        if (donorList.size() <= 1) {
            donorList = Collections.nCopies(profilesAmount, donorList.isEmpty() ? null : donorList.getFirst());
        } else if (donorList.size() != profilesAmount) {
            throw UserInputException.noStackTrace("DONOR_DNS values amount must be equal to CLIENT_ID values amount or contain exactly one provider");
        }
        ArrayList<DnsProfile> dnsProfiles = new ArrayList<>();

        for (int i = 0; i < profilesAmount; i++) {
            DnsProfile dnsProfile = DnsProfile.builder()
                    .dnsProvider(dnsList.get(i).toUpperCase())
                    .clientId(clientIdList.get(i))
                    .authSecret(secretList.get(i))
                    .donorDns(donorList.get(i))
                    .number(i + 1)
                    .build();
            dnsProfiles.add(dnsProfile);
        }
        return dnsProfiles;
    }

}
