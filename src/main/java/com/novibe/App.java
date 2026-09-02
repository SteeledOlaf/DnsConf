package com.novibe;


import com.novibe.common.DnsTaskRunner;
import com.novibe.common.base_structures.DnsProfile;
import com.novibe.common.config.AppSettings;
import com.novibe.common.exception.CredentialsException;
import com.novibe.common.exception.UserInputException;
import com.novibe.common.util.EnvParser;
import com.novibe.common.util.Log;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static java.util.Objects.nonNull;

public class App {

    public static void main(String[] args) {
        final AppSettings settings = AppSettings.fromEnvironment();
        final List<DnsProfile> dnsProfiles = EnvParser.parseProfiles(settings);
        final AnnotationConfigApplicationContext commonContext = loadCommonApplicationContext(settings);
        int failedProfiles = 0;

        for (DnsProfile dnsProfile : dnsProfiles) {
            AnnotationConfigApplicationContext currentContext = null;
            try {
                currentContext = loadCurrentProfileContext(dnsProfile, commonContext);

                DnsTaskRunner<?> runner = currentContext.getBean(DnsTaskRunner.class);
                runner.run();

            } catch (CredentialsException credentialsException) {
                Log.fail("CredentialsException on profile " + dnsProfile.number());
                Log.fail(credentialsException.getMessage());
                failedProfiles++;
            } catch (Exception exception) {
                Log.fail("Unexpected exception on profile " + dnsProfile.number());
                Log.fail(exception.getMessage());
                failedProfiles++;
            } finally {
                if (nonNull(currentContext)) currentContext.close();
            }
        }
        commonContext.close();
        if (failedProfiles > 0) {
            throw new IllegalStateException("Failed to configure %s of %s DNS profiles"
                    .formatted(failedProfiles, dnsProfiles.size()));
        }
    }

    private static AnnotationConfigApplicationContext loadCommonApplicationContext(AppSettings settings) {
        String commonsBasePackage = "com.novibe.common";
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(AppSettings.class, () -> settings);
        context.scan(commonsBasePackage);
        context.refresh();
        return context;
    }

    private static @NonNull AnnotationConfigApplicationContext loadCurrentProfileContext(DnsProfile dnsProfile, ApplicationContext commonContext) {
        String dnsBasePackage = switch (dnsProfile.dnsProvider()) {
            case "CLOUDFLARE" -> "com.novibe.dns.cloudflare";
            case "NEXTDNS" -> "com.novibe.dns.next_dns";
            default ->
                    throw UserInputException.noStackTrace("Unsupported DNS provider! Must be CLOUDFLARE or NEXTDNS. Was: " + dnsProfile.dnsProvider());
        };
        AnnotationConfigApplicationContext currentContext = new AnnotationConfigApplicationContext();
        currentContext.setParent(commonContext);
        currentContext.scan(dnsBasePackage);
        currentContext.registerBean(DnsProfile.class, () -> dnsProfile);
        currentContext.refresh();
        return currentContext;
    }

}
