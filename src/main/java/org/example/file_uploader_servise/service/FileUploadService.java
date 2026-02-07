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
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileUploadService {

    private final IdempotencyService idempotencyService;
    private final StorageService storageService;
    private final FileMetadataRepository fileMetadataRepository;
    private final UploadRequestRepository uploadRequestRepository;
    private final FileChecksumCalculator checksumCalculator;

    @Value("${storage.s3.bucket}")
    private String bucket;

    @Value("#{'${app.allowed-content-types}'.split(',')}")
    private List<String> allowedContentTypes;

    @Value("${app.upload.max-retries:3}")
    private int maxRetries;

    @Async("uploadExecutor")
    public CompletableFuture<UploadResponseDto> processUpload(
            String clientId,
            String uploadId,
            MultipartFile file,
            Map<String, String> metadata,
            Integer timeoutSeconds
    ) {

        UploadRequest uploadRequest = null;

        try {
            validateFile(file);

            String checksum = checksumCalculator.calculateSha256(file);

            Optional<String> duplicateFileId =
                    idempotencyService.findDuplicateByContent(clientId, checksum);

            if (duplicateFileId.isPresent()) {
                return handleDuplicate(duplicateFileId.get());
            }

            uploadRequest = idempotencyService.createOrGetUploadRequest(
                    clientId,
                    uploadId,
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSize(),
                    checksum
            );

            return handleUploadRequest(uploadRequest, file, metadata);

        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                    UploadResponseDto.failed(uploadRequest)
            );
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileUploadException("File is empty");
        }

        String contentType = Optional.ofNullable(file.getContentType())
                .orElse("application/octet-stream");

        if (!allowedContentTypes.contains(contentType)) {
            throw new FileUploadException("Content type not allowed");
        }
    }

    private CompletableFuture<UploadResponseDto> handleUploadRequest(
            UploadRequest uploadRequest,
            MultipartFile file,
            Map<String, String> metadata
    ) {

        switch (uploadRequest.getStatus()) {
            case COMPLETED:
                return getCompletedUploadResponse(uploadRequest);

            case PROCESSING:
                return CompletableFuture.completedFuture(
                        UploadResponseDto.processing(uploadRequest)
                );

            case FAILED:
                if (uploadRequest.canRetry()) {
                    return startUploadProcessing(uploadRequest, file, metadata);
                }
                return CompletableFuture.completedFuture(
                        UploadResponseDto.failed(uploadRequest)
                );

            case PENDING:
            case CANCELLED:
                return startUploadProcessing(uploadRequest, file, metadata);

            default:
                throw new IllegalStateException("Unknown upload status");
        }
    }

    private CompletableFuture<UploadResponseDto> startUploadProcessing(
            UploadRequest uploadRequest,
            MultipartFile file,
            Map<String, String> metadata
    ) {

        if (!idempotencyService.acquireForProcessing(uploadRequest.getId())) {
            return CompletableFuture.completedFuture(
                    UploadResponseDto.processing(uploadRequest)
            );
        }

        return uploadWithCompensation(uploadRequest, file, metadata)
                .exceptionally(ex -> {
                    idempotencyService.markAsFailed(uploadRequest.getId(), ex.getMessage());
                    return UploadResponseDto.failed(uploadRequest);
                });
    }

    @Transactional
    protected CompletableFuture<UploadResponseDto> uploadWithCompensation(
            UploadRequest uploadRequest,
            MultipartFile file,
            Map<String, String> metadata
    ) {

        FileMetadata fileMetadata = null;
        String objectKey = null;

        try {
            fileMetadata = createFileMetadata(uploadRequest, metadata);

            objectKey = generateObjectKey(
                    uploadRequest.getClientId(),
                    uploadRequest.getUploadId(),
                    file.getOriginalFilename()
            );

            FileMetadata.StorageInfo storageInfo =
                    storageService.uploadFile(bucket, objectKey, file, metadata);

            fileMetadata.setStorageInfo(storageInfo);

            FileMetadata saved = fileMetadataRepository.save(fileMetadata);

            idempotencyService.markAsCompleted(
                    uploadRequest.getId(),
                    saved.getId()
            );

            return CompletableFuture.completedFuture(
                    UploadResponseDto.completed(
                            uploadRequest,
                            saved.getId(),
                            storageInfo.getUrl()
                    )
            );

        } catch (Exception e) {
            cleanup(objectKey, fileMetadata);
            throw e;
        }
    }

    private CompletableFuture<UploadResponseDto> handleDuplicate(String fileMetadataId) {
        return fileMetadataRepository.findById(fileMetadataId)
                .map(meta -> CompletableFuture.completedFuture(
                        UploadResponseDto.completed(
                                uploadRequestRepository
                                        .findById(meta.getUploadRequestId())
                                        .orElseThrow(),
                                meta.getId(),
                                meta.getStorageInfo().getUrl()
                        )
                ))
                .orElseGet(() -> CompletableFuture.completedFuture(
                        UploadResponseDto.failed(null)
                ));
    }

    private CompletableFuture<UploadResponseDto> getCompletedUploadResponse(
            UploadRequest uploadRequest
    ) {
        return fileMetadataRepository.findByUploadRequestId(uploadRequest.getId())
                .map(meta -> CompletableFuture.completedFuture(
                        UploadResponseDto.completed(
                                uploadRequest,
                                meta.getId(),
                                meta.getStorageInfo().getUrl()
                        )
                ))
                .orElseGet(() -> CompletableFuture.completedFuture(
                        UploadResponseDto.failed(uploadRequest)
                ));
    }

    private void cleanup(String objectKey, FileMetadata fileMetadata) {
        try {
            if (objectKey != null) {
                storageService.deleteFile(bucket, objectKey);
            }
            if (fileMetadata != null && fileMetadata.getId() != null) {
                fileMetadataRepository.deleteById(fileMetadata.getId());
            }
        } catch (Exception e) {
            log.error("Cleanup failed", e);
        }
    }

    private FileMetadata createFileMetadata(
            UploadRequest uploadRequest,
            Map<String, String> metadata
    ) {

        FileMetadata meta = FileMetadata.create(
                uploadRequest.getId(),
                uploadRequest.getClientId(),
                uploadRequest.getUploadId(),
                uploadRequest.getOriginalFilename(),
                UUID.randomUUID().toString(),
                uploadRequest.getContentType(),
                uploadRequest.getFileSize(),
                uploadRequest.getChecksum()
        );

        if (metadata != null) {
            metadata.forEach(meta::addMetadata);
        }

        return meta;
    }

    private String generateObjectKey(String clientId, String uploadId, String filename) {
        LocalDateTime now = LocalDateTime.now();
        return String.format("%s/%d/%02d/%02d/%s/%s",
                clientId,
                now.getYear(),
                now.getMonthValue(),
                now.getDayOfMonth(),
                uploadId,
                filename.replaceAll("[^a-zA-Z0-9._-]", "_")
        );
    }
}
