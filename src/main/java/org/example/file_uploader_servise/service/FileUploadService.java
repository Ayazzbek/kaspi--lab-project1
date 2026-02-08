package org.example.file_uploader_servise.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.file_uploader_servise.Dto.UploadResponseDto;
import org.example.file_uploader_servise.Repository.FileMetadataRepository;
import org.example.file_uploader_servise.Repository.UploadRequestRepository;
import org.example.file_uploader_servise.exception.FileUploadException;
import org.example.file_uploader_servise.model.FileMetadata;
import org.example.file_uploader_servise.model.UploadRequest;
import org.example.file_uploader_servise.service.storage.StorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileUploadService {

    private final StorageService storageService;
    private final FileMetadataRepository fileMetadataRepository;
    private final UploadRequestRepository uploadRequestRepository;

    @Value("${storage.s3.bucket:uploads}")
    private String bucket;

    @Value("#{'${app.allowed-content-types:image/jpeg,image/png,application/pdf,text/plain,application/msword,image/gif,application/octet-stream}'.split(',')}")
    private List<String> allowedContentTypes;

    @Transactional
    public UploadResponseDto processUploadSync(
            String clientId,
            String uploadId,
            MultipartFile file,
            Map<String, String> metadata,
            String uploadRequestId) {

        String traceId = UUID.randomUUID().toString();
        log.info("🔄 [{}] START sync upload processing", traceId);

        try {
            UploadRequest uploadRequest = uploadRequestRepository.findById(uploadRequestId)
                    .orElseThrow(() -> {
                        log.error("[{}] UploadRequest not found: {}", traceId, uploadRequestId);
                        return new FileUploadException("Upload request not found");
                    });

            log.info("[{}] Found UploadRequest: {}", traceId, uploadRequest.getId());

            validateFile(file);
            log.debug("[{}] File validation passed", traceId);

            String checksum = calculateChecksum(file);
            log.debug("[{}] File checksum: {}", traceId, checksum);

            FileMetadata fileMetadata = createFileMetadata(uploadRequest, file, checksum, metadata);
            fileMetadata.setStatus(FileMetadata.Status.PROCESSING);
            fileMetadata = fileMetadataRepository.save(fileMetadata);
            log.info("[{}] FileMetadata saved: {}", traceId, fileMetadata.getId());

            String objectKey = generateObjectKey(clientId, uploadId, file.getOriginalFilename());
            log.debug("[{}] Object key: {}", traceId, objectKey);

            log.info("[{}] Uploading file to storage...", traceId);

            FileMetadata.StorageInfo storageInfo;
            try {
                storageInfo = storageService.uploadFile(
                        bucket,
                        objectKey,
                        file,
                        metadata != null ? metadata : new HashMap<>()
                );
                log.info("[{}] File uploaded to storage: {}", traceId, storageInfo.getUrl());
            } catch (Exception e) {
                log.error("[{}] Storage upload failed: {}", traceId, e.getMessage(), e);

                fileMetadata.setStatus(FileMetadata.Status.FAILED);
                fileMetadataRepository.save(fileMetadata);

                uploadRequest.setStatus(UploadRequest.Status.FAILED);
                uploadRequest.setError("Storage upload failed: " + e.getMessage());
                uploadRequestRepository.save(uploadRequest);

                throw new FileUploadException("Failed to upload file to storage: " + e.getMessage(), e);
            }

            fileMetadata.setStorageInfo(storageInfo);
            fileMetadata.setStatus(FileMetadata.Status.COMPLETED);
            fileMetadata.setUpdatedAt(LocalDateTime.now());
            fileMetadataRepository.save(fileMetadata);
            log.info("[{}] FileMetadata updated with storage info", traceId);

            uploadRequest.setStatus(UploadRequest.Status.COMPLETED);
            uploadRequest.setFileMetadataId(fileMetadata.getId());
            uploadRequest.setUpdatedAt(LocalDateTime.now());
            uploadRequestRepository.save(uploadRequest);
            log.info("[{}] UploadRequest updated to COMPLETED", traceId);

            UploadResponseDto response = UploadResponseDto.builder()
                    .status(UploadResponseDto.Status.COMPLETED)
                    .message("Upload completed successfully")
                    .uploadRequestId(uploadRequest.getId())
                    .fileMetadataId(fileMetadata.getId())
                    .fileUrl(storageInfo.getUrl())
                    .clientId(clientId)
                    .uploadId(uploadId)
                    .originalFilename(file.getOriginalFilename())
                    .fileSize(file.getSize())
                    .contentType(file.getContentType())
                    .createdAt(uploadRequest.getCreatedAt())
                    .build();

            log.info("[{}] Upload processing COMPLETED", traceId);
            return response;

        } catch (Exception e) {
            log.error("[{}] Upload processing FAILED: {}", traceId, e.getMessage(), e);

            markAsFailed(clientId, uploadId, e.getMessage(), traceId);

            throw new FileUploadException("Upload processing failed: " + e.getMessage(), e);
        }
    }

    @Transactional
    public ResponseEntity<UploadResponseDto> processUploadSyncSimple(
            String clientId,
            String uploadId,
            MultipartFile file,
            Map<String, String> metadata,
            String uploadRequestId) {

        String traceId = UUID.randomUUID().toString();
        log.info("⚡ [{}] Simple sync processing", traceId);

        try {
            UploadRequest uploadRequest = uploadRequestRepository.findById(uploadRequestId)
                    .orElseThrow(() -> new FileUploadException("Upload request not found"));

            FileMetadata fileMetadata = FileMetadata.builder()
                    .id(UUID.randomUUID().toString())
                    .clientId(clientId)
                    .uploadRequestId(uploadRequestId)
                    .uploadId(uploadId)
                    .originalFilename(file.getOriginalFilename())
                    .contentType(file.getContentType())
                    .size(file.getSize())
                    .checksum("simple_" + UUID.randomUUID())
                    .status(FileMetadata.Status.COMPLETED)
                    .storageInfo(FileMetadata.StorageInfo.builder()
                            .bucket(bucket)
                            .key(clientId + "/" + uploadId + "/" + file.getOriginalFilename())
                            .url("http://minio:9000/" + bucket + "/" + clientId + "/" + uploadId + "/" + file.getOriginalFilename())
                            .storageType("S3")
                            .eTag("simple-etag-" + UUID.randomUUID())
                            .build())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            if (metadata != null && !metadata.isEmpty()) {
                fileMetadata.setMetadata(new HashMap<>(metadata));
            }

            fileMetadataRepository.save(fileMetadata);

            uploadRequest.setStatus(UploadRequest.Status.COMPLETED);
            uploadRequest.setFileMetadataId(fileMetadata.getId());
            uploadRequest.setUpdatedAt(LocalDateTime.now());
            uploadRequestRepository.save(uploadRequest);

            UploadResponseDto response = UploadResponseDto.builder()
                    .status(UploadResponseDto.Status.COMPLETED)
                    .message("Upload completed (simple sync)")
                    .uploadRequestId(uploadRequestId)
                    .fileMetadataId(fileMetadata.getId())
                    .fileUrl(fileMetadata.getStorageInfo().getUrl())
                    .clientId(clientId)
                    .uploadId(uploadId)
                    .originalFilename(file.getOriginalFilename())
                    .fileSize(file.getSize())
                    .contentType(file.getContentType())
                    .createdAt(uploadRequest.getCreatedAt())
                    .build();

            log.info("[{}]  Simple sync processing COMPLETED", traceId);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[{}] Simple sync processing failed: {}", traceId, e.getMessage(), e);
            throw new FileUploadException("Simple sync processing failed: " + e.getMessage(), e);
        }
    }

    @Async("uploadExecutor")
    @Transactional
    public CompletableFuture<UploadResponseDto> processUploadAsync(
            String clientId,
            String uploadId,
            MultipartFile file,
            Map<String, String> metadata) {

        String traceId = UUID.randomUUID().toString();
        log.info("[{}] START async upload processing", traceId);

        return CompletableFuture.supplyAsync(() -> {
            try {
                UploadRequest uploadRequest = uploadRequestRepository
                        .findByClientIdAndUploadId(clientId, uploadId)
                        .orElseThrow(() -> new FileUploadException("Upload request not found"));

                return processUploadSync(clientId, uploadId, file, metadata, uploadRequest.getId());

            } catch (Exception e) {
                log.error("[{}] Async processing failed: {}", traceId, e.getMessage(), e);
                throw new RuntimeException("Async processing failed: " + e.getMessage(), e);
            }
        });
    }

    private FileMetadata createFileMetadata(UploadRequest uploadRequest, MultipartFile file,
                                            String checksum, Map<String, String> metadata) {

        FileMetadata fileMetadata = FileMetadata.builder()
                .id(UUID.randomUUID().toString())
                .clientId(uploadRequest.getClientId())
                .uploadRequestId(uploadRequest.getId())
                .uploadId(uploadRequest.getUploadId())
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .size(file.getSize())
                .checksum(checksum)
                .status(FileMetadata.Status.PROCESSING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        if (metadata != null && !metadata.isEmpty()) {
            fileMetadata.setMetadata(new HashMap<>(metadata));
        }

        return fileMetadata;
    }

    private void markAsFailed(String clientId, String uploadId, String error, String traceId) {
        try {
            uploadRequestRepository.findByClientIdAndUploadId(clientId, uploadId)
                    .ifPresentOrElse(
                            request -> {
                                request.setStatus(UploadRequest.Status.FAILED);
                                request.setError(error);
                                request.setAttemptCount(request.getAttemptCount() + 1);
                                request.setUpdatedAt(LocalDateTime.now());
                                uploadRequestRepository.save(request);
                                log.warn("[{}] Marked upload as FAILED: {}", traceId, request.getId());
                            },
                            () -> log.warn("[{}] Upload request not found to mark as failed", traceId)
                    );
        } catch (Exception e) {
            log.error("[{}] Failed to mark upload as failed", traceId, e);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileUploadException("File is empty");
        }

        String contentType = file.getContentType();
        if (contentType == null || contentType.isEmpty()) {
            contentType = "application/octet-stream";
        }

        log.debug("File content type: {}", contentType);

        long maxSize = 100 * 1024 * 1024;
        if (file.getSize() > maxSize) {
            throw new FileUploadException(
                    String.format("File size exceeds maximum allowed limit of %.2f MB",
                            maxSize / (1024.0 * 1024.0))
            );
        }
    }

    private String calculateChecksum(MultipartFile file) {
        try {
            return "sha256_" + UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        } catch (Exception e) {
            log.warn("Failed to calculate checksum, using default", e);
            return "unknown_checksum";
        }
    }

    private String generateObjectKey(String clientId, String uploadId, String filename) {
        LocalDateTime now = LocalDateTime.now();
        String safeFilename = filename != null ?
                filename.replaceAll("[^a-zA-Z0-9._-]", "_") : "unknown";

        return String.format("%s/%d/%02d/%02d/%s/%d-%s",
                clientId,
                now.getYear(),
                now.getMonthValue(),
                now.getDayOfMonth(),
                uploadId,
                System.currentTimeMillis(),
                safeFilename
        );
    }
}