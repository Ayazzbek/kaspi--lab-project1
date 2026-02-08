package org.example.file_uploader_servise.model;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

@Document(collection = "upload_requests")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadRequest {

    @Id
    private String id;

    @Field("client_id")
    private String clientId;

    @Field("upload_id")
    private String uploadId;

    @Field("original_filename")
    private String originalFilename;

    @Field("content_type")
    private String contentType;

    @Field("file_size")
    private Long fileSize;

    private String checksum;
    private String error;

    @Field("attempt_count")
    @Builder.Default
    private Integer attemptCount = 0;

    private Status status;

    @Field("file_metadata_id")
    private String fileMetadataId;

    @Field("created_at")
    private LocalDateTime createdAt;

    @Field("updated_at")
    private LocalDateTime updatedAt;

    @Field("completed_at")
    private LocalDateTime completedAt;

    public enum Status {
        PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED
    }

    public static UploadRequest create(
            String clientId,
            String uploadId,
            String originalFilename,
            String contentType,
            long fileSize,
            String checksum) {

        UploadRequest request = new UploadRequest();
        request.clientId = clientId;
        request.uploadId = uploadId;
        request.originalFilename = originalFilename;
        request.contentType = contentType;
        request.fileSize = fileSize;
        request.checksum = checksum;
        request.initialize();

        return request;
    }

    public void markProcessing() {
        this.status = Status.PROCESSING;
        this.updatedAt = LocalDateTime.now();
    }

    public void markCompleted(String fileMetadataId) {
        this.status = Status.COMPLETED;
        this.fileMetadataId = fileMetadataId;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = Status.FAILED;
        this.error = errorMessage;
        this.attemptCount = (this.attemptCount == null ? 0 : this.attemptCount) + 1;
        this.updatedAt = LocalDateTime.now();
    }

    public void markCancelled() {
        this.status = Status.CANCELLED;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean canRetry() {
        return status == Status.FAILED
                && (attemptCount == null || attemptCount < 3);
    }

    public void initialize() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.status == null) {
            this.status = Status.PENDING;
        }
        if (this.attemptCount == null) {
            this.attemptCount = 0;
        }
    }
}