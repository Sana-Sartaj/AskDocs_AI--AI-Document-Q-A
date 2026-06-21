package com.docqa.health;

import com.docqa.config.S3Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class S3HealthIndicatorTest {

    @Mock S3Client s3Client;

    private S3HealthIndicator indicator;
    private S3Properties s3Properties;

    @BeforeEach
    void setUp() {
        s3Properties = new S3Properties();
        s3Properties.setS3Bucket("my-bucket");
        s3Properties.setRegion("us-east-1");
        indicator = new S3HealthIndicator(s3Client, s3Properties);
    }

    @Test
    void health_bucketAccessible_returnsUp() {
        given(s3Client.headBucket(any(HeadBucketRequest.class))).willReturn(null);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("bucket", "my-bucket");
        assertThat(health.getDetails()).containsEntry("region", "us-east-1");
    }

    @Test
    void health_noSuchBucket_returnsDown() {
        NoSuchBucketException ex = NoSuchBucketException.builder().message("no such bucket").build();
        given(s3Client.headBucket(any(HeadBucketRequest.class))).willThrow(ex);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("error", "Bucket does not exist");
    }

    @Test
    void health_s3Exception_returnsDownWithStatusCode() {
        S3Exception s3ex = mock(S3Exception.class);
        AwsErrorDetails details = mock(AwsErrorDetails.class);
        when(details.errorMessage()).thenReturn("Access Denied");
        when(s3ex.statusCode()).thenReturn(403);
        when(s3ex.awsErrorDetails()).thenReturn(details);
        given(s3Client.headBucket(any(HeadBucketRequest.class))).willThrow(s3ex);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("statusCode", 403);
        assertThat(health.getDetails()).containsEntry("error", "Access Denied");
    }

    @Test
    void health_genericException_returnsDown() {
        given(s3Client.headBucket(any(HeadBucketRequest.class)))
                .willThrow(new RuntimeException("connection timeout"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("bucket", "my-bucket");
    }
}
