package org.example.file_uploader_servise.service.storage;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.file_uploader_servise.model.FileMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.ZoneId;
import java.util.Map;

@Slf4j
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "s3")
public class MinioStorageService implements StorageService {

    private final S3Client s3Client;
    private final String endpoint;
    private final String bucket;
    private final String region;

    public MinioStorageService(
            @Value("${storage.s3.endpoint}") String endpoint,
            @Value("${storage.s3.access-key}") String accessKey,
            @Value("${storage.s3.secret-key}") String secretKey,
            @Value("${storage.s3.region}") String region,
            @Value("${storage.s3.bucket}") String bucket
    ) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.region = region;

        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(
                        StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKey, secretKey)
                        )
                )
                .region(Region.of(region))
                .forcePathStyle(true)
                .build();
    }

    @PostConstruct
    public void init() {
        ensureBucketExists();
    }

    private void ensureBucketExists() {
        try {
            s3Client.headBucket(
                    HeadBucketRequest.builder()
                            .bucket(bucket)
                            .build()
            );
        } catch (NoSuchBucketException e) {
            s3Client.createBucket(
                    CreateBucketRequest.builder()
                            .bucket(bucket)
                            .build()
            );
            log.info("Bucket '{}' created", bucket);
        }
    }

    @Override
    public FileMetadata.StorageInfo uploadFile(
            String bucket,
            String objectKey,
            MultipartFile file,
            Map<String, String> metadata
    ) {
        try (InputStream inputStream = file.getInputStream()) {
            return uploadStream(
                    bucket,
                    objectKey,
                    inputStream,
                    file.getSize(),
                    file.getContentType(),
                    metadata
            );
        } catch (IOException e) {
            throw new StorageException("Failed to read file", e);
        }
    }

    @Override
    public FileMetadata.StorageInfo uploadStream(
            String bucket,
            String objectKey,
            InputStream inputStream,
            long size,
            String contentType,
            Map<String, String> metadata
    ) {
        try {
            PutObjectRequest.Builder requestBuilder = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(contentType)
                    .contentLength(size);

            if (metadata != null && !metadata.isEmpty()) {
                requestBuilder.metadata(metadata);
            }

            PutObjectResponse response = s3Client.putObject(
                    requestBuilder.build(),
                    RequestBody.fromInputStream(inputStream, size)
            );

            return FileMetadata.StorageInfo.builder()
                    .provider("minio")
                    .bucket(bucket)
                    .objectKey(objectKey)
                    .url(endpoint + "/" + bucket + "/" + objectKey)
                    .etag(response.eTag())
                    .region(region)
                    .build();

        } catch (S3Exception e) {
            throw new StorageException("Failed to upload file", e);
        }
    }

    @Override
    public boolean fileExists(String bucket, String objectKey) {
        try {
            s3Client.headObject(
                    HeadObjectRequest.builder()
                            .bucket(bucket)
                            .key(objectKey)
                            .build()
            );
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public void deleteFile(String bucket, String objectKey) {
        s3Client.deleteObject(
                DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build()
        );
    }

    @Override
    public FileMetadata.StorageInfo getFileInfo(String bucket, String objectKey) {
        HeadObjectResponse response = s3Client.headObject(
                HeadObjectRequest.builder()
                        .bucket(bucket)
                        .key(objectKey)
                        .build()
        );

        return FileMetadata.StorageInfo.builder()
                .provider("minio")
                .bucket(bucket)
                .objectKey(objectKey)
                .url(endpoint + "/" + bucket + "/" + objectKey)
                .etag(response.eTag())
                .region(region)
                .uploadedAt(
                        response.lastModified()
                                .atZone(ZoneId.systemDefault())
                                .toLocalDateTime()
                )
                .build();
    }

    @Override
    public Resource downloadFile(String bucket, String objectKey) {
        ResponseBytes<GetObjectResponse> bytes =
                s3Client.getObjectAsBytes(
                        GetObjectRequest.builder()
                                .bucket(bucket)
                                .key(objectKey)
                                .build()
                );

        return new ByteArrayResource(bytes.asByteArray()) {
            @Override
            public String getFilename() {
                return objectKey.substring(objectKey.lastIndexOf('/') + 1);
            }
        };
    }

    @Override
    public String getPresignedUrl(String bucket, String objectKey, int expirationMinutes) {
        return endpoint + "/" + bucket + "/" + objectKey;
    }
}
