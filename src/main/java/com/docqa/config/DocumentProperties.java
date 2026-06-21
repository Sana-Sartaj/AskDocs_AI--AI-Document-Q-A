package com.docqa.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.document")
@Getter
@Setter
public class DocumentProperties {

    private int chunkSize = 1000;
    private int chunkOverlap = 200;
}
