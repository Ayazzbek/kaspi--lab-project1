package org.example.file_uploader_servise.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "upload_requests")
@CompoundIndex(
        name = "client_upload_unique",
        def = "{'clientId': 1, 'uploadId': 1}",
        unique = true
)
@CompoundIndex(
        name = "status_updated_idx",
        def = "{'status': 1, 'updatedAt': 1}"
)
public class UploadRequest {

    @Id
    private String id;

    @Indexed
    private String clientId;

    private String uploadId;

    @Indexed
    private Status status;

    private String fileMetadataId;

    private String originalFilename;
    private String contentType;
    private Long fileSize;
    private String checksum;

    private Integer attemptCount;
    private String errorMessage;
    private Map<String, String> metadata;

    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;

    @Version
    private Long version;

    public enum Status {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public static UploadRequest create(String clientId, String uploadId, String filename,
                                       String contentType, long size, String checksum) {
        return UploadRequest.builder()
                .clientId(clientId)
                .uploadId(uploadId)
                .originalFilename(filename)
                .contentType(contentType)
                .fileSize(size)
                .checksum(checksum)
                .status(Status.PENDING)
                .attemptCount(0)
                .metadata(new HashMap<>())
                .build();
    }

    public void markProcessing() {
        validateTransition(Status.PROCESSING);
        this.status = Status.PROCESSING;
        this.attemptCount = (attemptCount == null ? 0 : attemptCount) + 1;
        this.updatedAt = LocalDateTime.now();
    }

    public void markCompleted(String fileMetadataId) {
        validateTransition(Status.COMPLETED);
        this.status = Status.COMPLETED;
        this.fileMetadataId = fileMetadataId;
        this.completedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        validateTransition(Status.FAILED);
        this.status = Status.FAILED;
        this.errorMessage = errorMessage;
        this.updatedAt = LocalDateTime.now();
    }

    public void cancel() {
        validateTransition(Status.CANCELLED);
        this.status = Status.CANCELLED;
        this.updatedAt = LocalDateTime.now();
    }

    private void validateTransition(Status newStatus) {
        if (!canTransitionTo(newStatus)) {
            throw new IllegalStateException(
                    String.format("Invalid state transition from %s to %s", status, newStatus)
            );
        }
    }

    public boolean canTransitionTo(Status newStatus) {
        return switch (this.status) {
            case PENDING -> newStatus == Status.PROCESSING ||
                    newStatus == Status.FAILED ||
                    newStatus == Status.CANCELLED;
            case PROCESSING -> newStatus == Status.COMPLETED ||
                    newStatus == Status.FAILED ||
                    newStatus == Status.CANCELLED;
            case COMPLETED, FAILED, CANCELLED -> false;
        };
    }

    public boolean canRetry() {
        return this.status == Status.FAILED &&
                (attemptCount == null || attemptCount < 3);
    }

    public void addMetadata(String key, String value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
    }
}