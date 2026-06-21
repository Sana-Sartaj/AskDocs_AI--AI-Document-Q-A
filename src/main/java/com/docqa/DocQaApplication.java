package com.docqa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class DocQaApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocQaApplication.class, args);
    }
}
