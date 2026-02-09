package com.example.acpcw1.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "acp")
public record AcpProperties(
        String postgres,
        String s3,
        String dynamodb,
        String urlEndpoint
) {}
