package com.docqa.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.aws")
@Getter
@Setter
public class S3Properties {

    private String region = "us-east-1";
    private String s3Bucket = "docqa-documents";
    private Duration presignedUrlTtl = Duration.ofHours(1);
    private long maxFileSizeMb = 50;
    private String endpointUrl = "";
    private boolean pathStyleAccess = false;
}
