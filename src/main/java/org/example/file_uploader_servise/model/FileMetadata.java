package org.example.file_uploader_servise.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
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
@Document(collection = "file_metadata")
@CompoundIndex(
        name = "upload_request_idx",
        def = "{'uploadRequestId': 1}",
        unique = true
)
@CompoundIndex(
        name = "checksum_client_idx",
        def = "{'checksum': 1, 'clientId': 1}"
)
public class FileMetadata {

    @Id
    private String id;

    @Indexed(name = "upload_request_idx", unique = true)
    private String uploadRequestId;

    private String clientId;
    private String uploadId;

    private String originalFilename;
    private String storageFilename;
    private String contentType;
    private Long size;

    @Indexed
    private String checksum;

    private StorageInfo storageInfo;
    private Map<String, String> metadata;

    @CreatedDate
    private LocalDateTime uploadedAt;

    @Version
    private Long version;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StorageInfo {
        private String provider;
        private String bucket;
        private String objectKey;
        private String url;
        private String region;
        private String etag;
        private LocalDateTime uploadedAt;

        public static StorageInfo createMinioInfo(String bucket, String objectKey,
                                                  String endpoint, String etag) {
            return StorageInfo.builder()
                    .provider("minio")
                    .bucket(bucket)
                    .objectKey(objectKey)
                    .url(String.format("%s/%s/%s", endpoint, bucket, objectKey))
                    .region("us-east-1")
                    .etag(etag)
                    .uploadedAt(LocalDateTime.now())
                    .build();
        }
    }

    public static FileMetadata create(String uploadRequestId, String clientId, String uploadId,
                                      String originalFilename, String storageFilename,
                                      String contentType, long size, String checksum) {
        return FileMetadata.builder()
                .uploadRequestId(uploadRequestId)
                .clientId(clientId)
                .uploadId(uploadId)
                .originalFilename(originalFilename)
                .storageFilename(storageFilename)
                .contentType(contentType)
                .size(size)
                .checksum(checksum)
                .metadata(new HashMap<>())
                .uploadedAt(LocalDateTime.now())
                .build();
    }

    public void addMetadata(String key, String value) {
        if (this.metadata == null) {
            this.metadata = new HashMap<>();
        }
        this.metadata.put(key, value);
    }
}