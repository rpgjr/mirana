package com.rpgjr.mirana.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub App settings. The webhook secret alone is enough to receive pull request events. The rest turns on the
 * Settings, GitHub page: connecting an account, listing repositories and reading pull requests.
 */
@ConfigurationProperties("mirana.github")
public record GithubProperties(String webhookSecret, String appId, String appSlug, String clientId,
                               String clientSecret, String privateKey) {

    public boolean connectConfigured() {
        return filled(appId) && filled(appSlug) && filled(clientId) && filled(clientSecret) && filled(privateKey);
    }

    private static boolean filled(String s) {
        return s != null && !s.isBlank();
    }
}
