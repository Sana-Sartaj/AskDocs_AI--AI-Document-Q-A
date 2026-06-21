package com.docqa;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=false",
        "app.jwt.secret=dGVzdFNlY3JldEtleVRoYXRJc0F0TGVhc3QyNTZCaXRzTG9uZw==",
        "langchain4j.open-ai.api-key=test",
        "app.aws.region=us-east-1",
        "app.aws.s3-bucket=test-bucket"
})
class DocQaApplicationTests {

    @Test
    void contextLoads() {
    }
}
