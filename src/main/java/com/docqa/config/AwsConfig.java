package com.docqa.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
@RequiredArgsConstructor
public class AwsConfig {

    private final S3Properties s3Properties;

    @Bean
    public S3Client s3Client() {
        var builder = S3Client.builder()
                .region(Region.of(s3Properties.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (StringUtils.hasText(s3Properties.getEndpointUrl())) {
            builder.endpointOverride(URI.create(s3Properties.getEndpointUrl()))
                   .serviceConfiguration(S3Configuration.builder()
                           .pathStyleAccessEnabled(s3Properties.isPathStyleAccess())
                           .build());
        }
        return builder.build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        var builder = S3Presigner.builder()
                .region(Region.of(s3Properties.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (StringUtils.hasText(s3Properties.getEndpointUrl())) {
            builder.endpointOverride(URI.create(s3Properties.getEndpointUrl()))
                   .serviceConfiguration(S3Configuration.builder()
                           .pathStyleAccessEnabled(s3Properties.isPathStyleAccess())
                           .build());
        }
        return builder.build();
    }
}
