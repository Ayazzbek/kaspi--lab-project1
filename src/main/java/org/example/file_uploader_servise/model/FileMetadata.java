package org.example.file_uploader_servise.model;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Document(collection = "file_metadata")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileMetadata {

    @Id
    private String id;

    @Field("client_id")
    private String clientId;

    @Field("upload_request_id")
    private String uploadRequestId;

    @Field("upload_id")
    private String uploadId;  // Добавьте это поле!

    @Field("original_filename")
    private String originalFilename;

    @Field("content_type")
    private String contentType;

    private Long size;
    private String checksum;

    @Field("storage_info")
    private StorageInfo storageInfo;

    // Добавьте поле статуса!
    @Field("status")
    @Builder.Default
    private Status status = Status.PENDING;

    @Field("metadata")
    @Builder.Default
    private Map<String, String> metadata = new HashMap<>();

    @Field("created_at")
    private LocalDateTime createdAt;

    @Field("updated_at")
    private LocalDateTime updatedAt;

    public void addMetadata(String key, String value) {
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        metadata.put(key, value);
    }

    // Enum для статусов
    public enum Status {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED,
        DELETED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StorageInfo {
        @Field("bucket")
        private String bucket;

        @Field("object_key")
        private String key;

        @Field("url")
        private String url;

        @Field("storage_type")
        private String storageType;

        @Field("version_id")
        private String versionId;

        @Field("e_tag")
        private String eTag;
    }
}