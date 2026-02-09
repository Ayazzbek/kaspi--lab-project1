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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileUploadService {

    private final StorageService storageService;
    private final FileMetadataRepository fileMetadataRepository;
    private final UploadRequestRepository uploadRequestRepository;

    @Value("${storage.s3.bucket:uploads}")
    private String bucket;

    @Transactional
    public UploadResponseDto upload(
            String clientId,
            String uploadId,
            MultipartFile file,
            Map<String, String> metadata,
            String uploadRequestId
    ) {

        log.info(" Start upload: clientId={}, uploadId={}", clientId, uploadId);

        UploadRequest uploadRequest = uploadRequestRepository.findById(uploadRequestId)
                .orElseThrow(() -> new FileUploadException("UploadRequest not found: " + uploadRequestId));

        try {
            validateFile(file);

            FileMetadata fileMetadata = createProcessingMetadata(uploadRequest, file, metadata);
            fileMetadataRepository.save(fileMetadata);

            String objectKey = generateObjectKey(
                    clientId,
                    uploadId,
                    file.getOriginalFilename()
            );

            FileMetadata.StorageInfo storageInfo = storageService.uploadFile(
                    bucket,
                    objectKey,
                    file,
                    metadata != null ? metadata : new HashMap<>()
            );

            fileMetadata.setStatus(FileMetadata.Status.COMPLETED);
            fileMetadata.setStorageInfo(storageInfo);
            fileMetadata.setUpdatedAt(LocalDateTime.now());
            fileMetadataRepository.save(fileMetadata);

            uploadRequest.setStatus(UploadRequest.Status.COMPLETED);
            uploadRequest.setFileMetadataId(fileMetadata.getId());
            uploadRequest.setUpdatedAt(LocalDateTime.now());
            uploadRequestRepository.save(uploadRequest);

            log.info("Upload completed: requestId={}", uploadRequestId);

            return buildSuccessResponse(uploadRequest, fileMetadata);

        } catch (Exception e) {
            log.error(" Upload failed: requestId={}, error={}", uploadRequestId, e.getMessage(), e);
            markFailed(uploadRequest, e.getMessage());
            throw new FileUploadException("Upload failed: " + e.getMessage(), e);
        }
    }


    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new FileUploadException("File is required");
        }

        long maxSize = 100L * 1024 * 1024;
        if (file.getSize() > maxSize) {
            throw new FileUploadException("File size exceeds 100MB");
        }
    }

    private FileMetadata createProcessingMetadata(
            UploadRequest uploadRequest,
            MultipartFile file,
            Map<String, String> metadata
    ) {

        FileMetadata fileMetadata = FileMetadata.builder()
                .id(UUID.randomUUID().toString())
                .clientId(uploadRequest.getClientId())
                .uploadRequestId(uploadRequest.getId())
                .uploadId(uploadRequest.getUploadId())
                .originalFilename(file.getOriginalFilename())
                .contentType(file.getContentType())
                .size(file.getSize())
                .status(FileMetadata.Status.PROCESSING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        if (metadata != null && !metadata.isEmpty()) {
            fileMetadata.setMetadata(new HashMap<>(metadata));
        }

        return fileMetadata;
    }

    private void markFailed(UploadRequest uploadRequest, String error) {
        uploadRequest.setStatus(UploadRequest.Status.FAILED);
        uploadRequest.setError(error);
        uploadRequest.setAttemptCount(uploadRequest.getAttemptCount() + 1);
        uploadRequest.setUpdatedAt(LocalDateTime.now());
        uploadRequestRepository.save(uploadRequest);
    }

    private UploadResponseDto buildSuccessResponse(
            UploadRequest uploadRequest,
            FileMetadata fileMetadata
    ) {

        return UploadResponseDto.builder()
                .status(UploadResponseDto.Status.COMPLETED)
                .message("Upload completed successfully")
                .uploadRequestId(uploadRequest.getId())
                .fileMetadataId(fileMetadata.getId())
                .fileUrl(fileMetadata.getStorageInfo().getUrl())
                .clientId(uploadRequest.getClientId())
                .uploadId(uploadRequest.getUploadId())
                .originalFilename(fileMetadata.getOriginalFilename())
                .fileSize(fileMetadata.getSize())
                .contentType(fileMetadata.getContentType())
                .createdAt(uploadRequest.getCreatedAt())
                .build();
    }

    private String generateObjectKey(String clientId, String uploadId, String filename) {
        String safeFilename = filename != null
                ? filename.replaceAll("[^a-zA-Z0-9._-]", "_")
                : "file";

        return String.format(
                "%s/%s/%d-%s",
                clientId,
                uploadId,
                System.currentTimeMillis(),
                safeFilename
        );
    }

    @Async("uploadExecutor")
    public UploadResponseDto processUploadSync(
            String clientId,
            String uploadId,
            MultipartFile file,
            Map<String, String> metadata,
            String uploadRequestId
    ) {
        return upload(
                clientId,
                uploadId,
                file,
                metadata,
                uploadRequestId
        );
    }
}
