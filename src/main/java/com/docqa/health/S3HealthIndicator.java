package com.docqa.health;

import com.docqa.config.S3Properties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Verifies S3 bucket accessibility by issuing a lightweight {@code HeadBucket}
 * request. A down status means document uploads and downloads are unavailable.
 */
@Component("s3")
@RequiredArgsConstructor
public class S3HealthIndicator implements HealthIndicator {

    private final S3Client s3Client;
    private final S3Properties s3Properties;

    @Override
    public Health health() {
        String bucket = s3Properties.getS3Bucket();
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return Health.up()
                    .withDetail("bucket", bucket)
                    .withDetail("region", s3Properties.getRegion())
                    .build();

        } catch (NoSuchBucketException ex) {
            return Health.down()
                    .withDetail("bucket", bucket)
                    .withDetail("error", "Bucket does not exist")
                    .build();

        } catch (S3Exception ex) {
            String errorMsg = ex.awsErrorDetails() != null && ex.awsErrorDetails().errorMessage() != null
                    ? ex.awsErrorDetails().errorMessage() : ex.getMessage();
            return Health.down()
                    .withDetail("bucket", bucket)
                    .withDetail("statusCode", ex.statusCode())
                    .withDetail("error", errorMsg)
                    .build();

        } catch (Exception ex) {
            return Health.down(ex)
                    .withDetail("bucket", bucket)
                    .build();
        }
    }
}
