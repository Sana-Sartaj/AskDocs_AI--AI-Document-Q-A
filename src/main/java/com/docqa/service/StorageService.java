package com.docqa.service;

import com.docqa.config.S3Properties;
import com.docqa.dto.response.PresignedUrlResponse;
import com.docqa.exception.ApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class StorageService {

    private static final long BYTES_PER_MB = 1024L * 1024L;

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/plain",
            "text/markdown",
            "text/csv"
    );

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final S3Properties s3Properties;

    /**
     * Validates, uploads the file to S3, and returns the generated S3 key.
     */
    public String upload(MultipartFile file, UUID userId) throws IOException {
        validateFile(file);

        String s3Key = buildS3Key(userId, sanitizeFilename(file.getOriginalFilename()));

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(s3Properties.getS3Bucket())
                .key(s3Key)
                .contentType(file.getContentType())
                .contentLength(file.getSize())
                .build();

        try {
            s3Client.putObject(putRequest, RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (SdkException e) {
            log.error("S3 upload failed for user={}: {}", userId, e.getMessage(), e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "File upload failed");
        }

        log.info("Uploaded to S3: key={}, size={}B, type={}", s3Key, file.getSize(), file.getContentType());
        return s3Key;
    }

    /**
     * Deletes an object from S3. Idempotent — does not throw if the key is already absent.
     */
    public void delete(String s3Key) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(s3Properties.getS3Bucket())
                    .key(s3Key)
                    .build());
            log.info("Deleted S3 object: {}", s3Key);
        } catch (SdkException e) {
            log.error("S3 delete failed for key={}: {}", s3Key, e.getMessage(), e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "File deletion failed");
        }
    }

    /**
     * Generates a presigned GET URL using the default TTL from configuration.
     */
    public PresignedUrlResponse generatePresignedUrl(String s3Key) {
        return generatePresignedUrl(s3Key, s3Properties.getPresignedUrlTtl());
    }

    /**
     * Generates a presigned GET URL with an explicit TTL.
     */
    public PresignedUrlResponse generatePresignedUrl(String s3Key, Duration ttl) {
        try {
            String url = s3Presigner.presignGetObject(
                    GetObjectPresignRequest.builder()
                            .signatureDuration(ttl)
                            .getObjectRequest(GetObjectRequest.builder()
                                    .bucket(s3Properties.getS3Bucket())
                                    .key(s3Key)
                                    .build())
                            .build())
                    .url()
                    .toString();

            log.debug("Generated presigned URL: key={}, ttl={}", s3Key, ttl);
            return new PresignedUrlResponse(url, s3Key, Instant.now().plus(ttl));
        } catch (SdkException e) {
            log.error("Failed to generate presigned URL for key={}: {}", s3Key, e.getMessage(), e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to generate download URL");
        }
    }

    /**
     * Downloads an object from S3 as raw bytes. Use presigned URLs for client-facing downloads.
     */
    public byte[] download(String s3Key) {
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(s3Properties.getS3Bucket())
                    .key(s3Key)
                    .build()).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "File not found: " + s3Key);
        } catch (SdkException e) {
            log.error("S3 download failed for key={}: {}", s3Key, e.getMessage(), e);
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "File download failed");
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "File must not be empty");
        }

        long maxBytes = s3Properties.getMaxFileSizeMb() * BYTES_PER_MB;
        if (file.getSize() > maxBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "File size " + (file.getSize() / BYTES_PER_MB) + " MB exceeds the "
                            + s3Properties.getMaxFileSizeMb() + " MB limit");
        }

        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported file type '" + contentType + "'. Allowed: "
                            + String.join(", ", ALLOWED_CONTENT_TYPES));
        }
    }

    private String buildS3Key(UUID userId, String filename) {
        return String.format("documents/%s/%s/%s", userId, UUID.randomUUID(), filename);
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "unnamed";
        }
        // Strip directory components to prevent path traversal
        String name = Paths.get(filename).getFileName().toString();
        // Keep only safe S3 key characters
        return name.replaceAll("[^a-zA-Z0-9._-]", "_").replaceAll("_{2,}", "_");
    }
}
