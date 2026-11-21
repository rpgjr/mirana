package com.rpgjr.mirana.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mirana.github")
public record GithubProperties(String webhookSecret) {
}
