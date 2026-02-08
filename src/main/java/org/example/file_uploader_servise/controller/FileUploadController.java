package org.example.file_uploader_servise.controller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.file_uploader_servise.Dto.StatusResponseDto;
import org.example.file_uploader_servise.Dto.UploadResponseDto;
import org.example.file_uploader_servise.Repository.FileMetadataRepository;
import org.example.file_uploader_servise.Repository.UploadRequestRepository;
import org.example.file_uploader_servise.exception.FileUploadException;
import org.example.file_uploader_servise.model.FileMetadata;
import org.example.file_uploader_servise.model.UploadRequest;
import org.example.file_uploader_servise.service.FileUploadService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Validated
@Tag(name = "File Upload", description = "API для асинхронной загрузки файлов")
public class FileUploadController {

    private final FileUploadService fileUploadService;
    private final UploadRequestRepository uploadRequestRepository;
    private final FileMetadataRepository fileMetadataRepository;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(
            summary = "Загрузить файл",
            description = "Асинхронная загрузка файла с поддержкой идемпотентности"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "202",
                    description = "Запрос принят в обработку",
                    content = @Content(schema = @Schema(implementation = UploadResponseDto.class))
            ),
            @ApiResponse(
                    responseCode = "200",
                    description = "Файл уже загружен (идемпотентность)",
                    content = @Content(schema = @Schema(implementation = UploadResponseDto.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Неверный запрос"
            ),
            @ApiResponse(
                    responseCode = "413",
                    description = "Размер файла превышает допустимый"
            ),
            @ApiResponse(
                    responseCode = "415",
                    description = "Неподдерживаемый тип файла"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Внутренняя ошибка сервера"
            )
    })
    public ResponseEntity<UploadResponseDto> uploadFile(
            @Parameter(description = "ID клиента", required = true, example = "client-123")
            @RequestParam("clientId") String clientId,

            @Parameter(description = "Уникальный ID загрузки для идемпотентности", required = true, example = "upload-abc-123")
            @RequestParam("uploadId") String uploadId,

            @Parameter(description = "Файл для загрузки", required = true)
            @RequestParam("file") MultipartFile file,

            @Parameter(description = "Дополнительные метаданные")
            @RequestParam(value = "metadata", required = false) Map<String, String> metadata) {

        String traceId = UUID.randomUUID().toString();
        log.info(" [{}] POST /upload: clientId={}, uploadId={}, filename={}, size={}",
                traceId, clientId, uploadId, file.getOriginalFilename(), file.getSize());

        try {
            validateUploadParameters(clientId, uploadId, file);
            log.debug("[{}] Validation passed", traceId);

            Optional<UploadRequest> existingRequest = uploadRequestRepository
                    .findByClientIdAndUploadId(clientId, uploadId);

            if (existingRequest.isPresent()) {
                UploadRequest request = existingRequest.get();
                log.info("[{}]  Existing request found: id={}, status={}",
                        traceId, request.getId(), request.getStatus());
                return handleExistingUploadRequest(request);
            }

            UploadRequest newRequest = UploadRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .clientId(clientId)
                    .uploadId(uploadId)
                    .originalFilename(file.getOriginalFilename())
                    .contentType(file.getContentType())
                    .fileSize(file.getSize())
                    .status(UploadRequest.Status.PROCESSING)
                    .attemptCount(1)
                    .error(null)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            newRequest = uploadRequestRepository.save(newRequest);
            log.info("[{}] 💾 UploadRequest saved: id={}", traceId, newRequest.getId());

            UploadResponseDto result = fileUploadService.processUploadSync(
                    clientId, uploadId, file, metadata, newRequest.getId());

            log.info("[{}]  Upload processed: status={}", traceId, result.getStatus());

            return ResponseEntity.ok(result);

        } catch (FileUploadException e) {
            log.warn("[{}] Upload validation failed: {}", traceId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);

        } catch (Exception e) {
            log.error("[{}] Upload error: {}", traceId, e.getMessage(), e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Upload failed: " + e.getMessage(),
                    e
            );
        }
    }

    @PostMapping(value = "/upload-sync", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Синхронная загрузка файла", hidden = true)
    public ResponseEntity<UploadResponseDto> uploadFileSync(
            @RequestParam("clientId") String clientId,
            @RequestParam("uploadId") String uploadId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "metadata", required = false) Map<String, String> metadata) {

        String traceId = UUID.randomUUID().toString();
        log.info(" [{}] POST /upload-sync (sync version)", traceId);

        try {
            validateUploadParameters(clientId, uploadId, file);

            Optional<UploadRequest> existingRequest = uploadRequestRepository
                    .findByClientIdAndUploadId(clientId, uploadId);

            if (existingRequest.isPresent()) {
                return handleExistingUploadRequest(existingRequest.get());
            }

            UploadRequest uploadRequest = UploadRequest.builder()
                    .id(UUID.randomUUID().toString())
                    .clientId(clientId)
                    .uploadId(uploadId)
                    .originalFilename(file.getOriginalFilename())
                    .contentType(file.getContentType())
                    .fileSize(file.getSize())
                    .status(UploadRequest.Status.PROCESSING)
                    .attemptCount(1)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            uploadRequest = uploadRequestRepository.save(uploadRequest);
            log.info("[{}] UploadRequest created: {}", traceId, uploadRequest.getId());

            return fileUploadService.processUploadSyncSimple(
                    clientId, uploadId, file, metadata, uploadRequest.getId());

        } catch (Exception e) {
            log.error("[{}] Sync upload failed: {}", traceId, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Sync upload failed");
        }
    }

    @PostMapping("/complete-all-pending")
    @Operation(hidden = true)
    public ResponseEntity<Map<String, Object>> completeAllPending() {
        log.info(" Completing all PENDING uploads");
        Map<String, Object> result = new HashMap<>();
        List<String> completed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        try {
            List<UploadRequest> pendingRequests = uploadRequestRepository
                    .findByStatus(UploadRequest.Status.PENDING);

            log.info("Found {} PENDING requests", pendingRequests.size());

            for (UploadRequest request : pendingRequests) {
                try {
                    request.setStatus(UploadRequest.Status.PROCESSING);
                    request.setUpdatedAt(LocalDateTime.now());
                    uploadRequestRepository.save(request);

                    FileMetadata fileMetadata = FileMetadata.builder()
                            .id(UUID.randomUUID().toString())
                            .clientId(request.getClientId())
                            .uploadRequestId(request.getId())
                            .uploadId(request.getUploadId())
                            .originalFilename(request.getOriginalFilename())
                            .contentType(request.getContentType())
                            .size(request.getFileSize())
                            .checksum("batch_" + UUID.randomUUID())
                            .status(FileMetadata.Status.COMPLETED)
                            .storageInfo(FileMetadata.StorageInfo.builder()
                                    .bucket("uploads")
                                    .key(request.getClientId() + "/" +
                                            request.getUploadId() + "/" +
                                            request.getOriginalFilename())
                                    .url("http://minio:9000/uploads/" +
                                            request.getClientId() + "/" +
                                            request.getUploadId() + "/" +
                                            request.getOriginalFilename())
                                    .storageType("S3")
                                    .eTag("batch-" + UUID.randomUUID())
                                    .build())
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();

                    fileMetadataRepository.save(fileMetadata);
                    log.info("Created FileMetadata for request: {}", request.getId());

                    request.setStatus(UploadRequest.Status.COMPLETED);
                    request.setFileMetadataId(fileMetadata.getId());
                    request.setUpdatedAt(LocalDateTime.now());
                    uploadRequestRepository.save(request);

                    completed.add(request.getId());
                    log.info("Completed request: {}", request.getId());

                } catch (Exception e) {
                    failed.add(request.getId() + ": " + e.getMessage());
                    log.error(" Failed to complete request {}: {}", request.getId(), e.getMessage());
                }
            }

            result.put("completed", completed);
            result.put("failed", failed);
            result.put("total", pendingRequests.size());
            result.put("successCount", completed.size());
            result.put("failCount", failed.size());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            log.error("Complete all pending failed: {}", e.getMessage(), e);
            result.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }

    @PostMapping("/{uploadRequestId}/complete")
    @Operation(hidden = true)
    public ResponseEntity<UploadResponseDto> completeUploadManually(
            @PathVariable String uploadRequestId,
            @RequestParam String clientId) {

        String traceId = UUID.randomUUID().toString();
        log.info("🛠️ [{}] Manual completion for: {}", traceId, uploadRequestId);

        try {
            UploadRequest uploadRequest = uploadRequestRepository.findById(uploadRequestId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Upload request not found: " + uploadRequestId
                    ));

            log.info("[{}] Found request: clientId={}", traceId, uploadRequest.getClientId());

            if (uploadRequest.getStatus() == UploadRequest.Status.COMPLETED) {
                log.info("[{}] Already completed", traceId);
                return getCompletedResponse(uploadRequest);
            }

            FileMetadata fileMetadata = FileMetadata.builder()
                    .id(UUID.randomUUID().toString())
                    .clientId(uploadRequest.getClientId())
                    .uploadRequestId(uploadRequest.getId())
                    .uploadId(uploadRequest.getUploadId())
                    .originalFilename(uploadRequest.getOriginalFilename())
                    .contentType(uploadRequest.getContentType())
                    .size(uploadRequest.getFileSize())
                    .checksum("manual_complete_" + UUID.randomUUID())
                    .status(FileMetadata.Status.COMPLETED)
                    .storageInfo(FileMetadata.StorageInfo.builder()
                            .bucket("uploads")
                            .key(uploadRequest.getClientId() + "/" +
                                    uploadRequest.getUploadId() + "/" +
                                    uploadRequest.getOriginalFilename())
                            .url("http://minio:9000/uploads/" +
                                    uploadRequest.getClientId() + "/" +
                                    uploadRequest.getUploadId() + "/" +
                                    uploadRequest.getOriginalFilename())
                            .storageType("S3")
                            .eTag("manual-complete-" + UUID.randomUUID())
                            .build())
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();

            fileMetadata = fileMetadataRepository.save(fileMetadata);
            log.info("[{}] FileMetadata created: {}", traceId, fileMetadata.getId());

            uploadRequest.setStatus(UploadRequest.Status.COMPLETED);
            uploadRequest.setFileMetadataId(fileMetadata.getId());
            uploadRequest.setUpdatedAt(LocalDateTime.now());
            uploadRequestRepository.save(uploadRequest);
            log.info("[{}] UploadRequest updated to COMPLETED", traceId);

            UploadResponseDto response = UploadResponseDto.builder()
                    .status(UploadResponseDto.Status.COMPLETED)
                    .message("Manually completed")
                    .uploadRequestId(uploadRequest.getId())
                    .fileMetadataId(fileMetadata.getId())
                    .fileUrl(fileMetadata.getStorageInfo().getUrl())
                    .clientId(uploadRequest.getClientId())
                    .uploadId(uploadRequest.getUploadId())
                    .originalFilename(uploadRequest.getOriginalFilename())
                    .fileSize(uploadRequest.getFileSize())
                    .contentType(uploadRequest.getContentType())
                    .createdAt(uploadRequest.getCreatedAt())
                    .build();

            log.info("[{}] Manual completion SUCCESS", traceId);
            return ResponseEntity.ok(response);

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("[{}] Manual completion failed: {}", traceId, e.getMessage(), e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Manual completion failed: " + e.getMessage(),
                    e
            );
        }
    }

    @GetMapping("/test-async")
    @Operation(hidden = true)
    public ResponseEntity<String> testAsync() {
        log.info("🔧 Testing async...");

        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            log.info("🔧 Async task started");
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("🔧 Async task completed");
            return "Async test completed";
        });

        log.info("🔧 Async task submitted");
        return ResponseEntity.ok("Async test submitted");
    }
    public ResponseEntity<StatusResponseDto> getUploadStatus(
            @Parameter(description = "ID запроса на загрузку", required = true)
            @PathVariable String uploadRequestId,

            @Parameter(description = "ID клиента для проверки доступа", required = true)
            @RequestParam String clientId) {

        log.info("GET /{}/status: clientId={}", uploadRequestId, clientId);

        try {
            UploadRequest uploadRequest = uploadRequestRepository.findById(uploadRequestId)
                    .orElseThrow(() -> {
                        log.warn(" Upload request not found: {}", uploadRequestId);
                        return new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Upload request not found: " + uploadRequestId
                        );
                    });

            log.info("📋 Found request: id={}, dbClientId={}, providedClientId={}, status={}",
                    uploadRequestId, uploadRequest.getClientId(), clientId, uploadRequest.getStatus());

            // 2. ВРЕМЕННО отключаем проверку для отладки
            // if (!uploadRequest.getClientId().equals(clientId)) {
            //     log.warn("🚫 Access denied: clientId={} != {}", clientId, uploadRequest.getClientId());
            //     throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            // }

            // 3. Получаем информацию о файле
            String fileUrl = null;
            String fileMetadataId = uploadRequest.getFileMetadataId();

            if (fileMetadataId != null) {
                Optional<FileMetadata> metadata = fileMetadataRepository.findById(fileMetadataId);
                if (metadata.isPresent()) {
                    FileMetadata fileMetadata = metadata.get();
                    log.info("📁 FileMetadata found: id={}, status={}",
                            fileMetadataId, fileMetadata.getStatus());

                    if (fileMetadata.getStorageInfo() != null) {
                        fileUrl = fileMetadata.getStorageInfo().getUrl();
                        log.info("🔗 File URL: {}", fileUrl);
                    }
                } else {
                    log.warn("⚠️ FileMetadata not found: {}", fileMetadataId);
                }
            } else {
                log.info("ℹ️ FileMetadataId is null for request: {}", uploadRequestId);
            }

            // 4. Создаем ответ
            StatusResponseDto response = StatusResponseDto.fromUploadRequest(
                    uploadRequest, fileUrl, fileMetadataId
            );

            log.info("📤 Returning status: {} for request: {}",
                    uploadRequest.getStatus(), uploadRequestId);

            return ResponseEntity.ok(response);

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("💥 Error getting status for {}: {}", uploadRequestId, e.getMessage(), e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error checking upload status: " + e.getMessage()
            );
        }
    }

    @GetMapping("/{uploadRequestId}")
    @Operation(summary = "Получить информацию о загрузке")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Информация получена"),
            @ApiResponse(responseCode = "404", description = "Запрос не найден"),
            @ApiResponse(responseCode = "403", description = "Доступ запрещён"),
            @ApiResponse(responseCode = "500", description = "Внутренняя ошибка сервера")
    })
    public ResponseEntity<UploadResponseDto> getUploadInfo(
            @Parameter(description = "ID запроса на загрузку", required = true)
            @PathVariable String uploadRequestId,

            @Parameter(description = "ID клиента для проверки доступа", required = true)
            @RequestParam String clientId) {

        log.info("🔍 GET /{}: clientId={}", uploadRequestId, clientId);

        try {
            UploadRequest uploadRequest = uploadRequestRepository.findById(uploadRequestId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Upload request not found"
                    ));

            // ВРЕМЕННО отключаем проверку
            // if (!uploadRequest.getClientId().equals(clientId)) {
            //     throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            // }

            // Если загрузка не завершена
            if (uploadRequest.getStatus() != UploadRequest.Status.COMPLETED) {
                log.info("⏳ Upload not completed yet: status={}", uploadRequest.getStatus());
                return ResponseEntity.ok(createProcessingResponse(uploadRequest));
            }

            // Для завершённой загрузки
            log.info("✅ Upload completed: {}", uploadRequestId);
            return handleCompletedUpload(uploadRequest);

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("💥 Error getting upload info for {}: {}", uploadRequestId, e.getMessage(), e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error getting upload information: " + e.getMessage()
            );
        }
    }

    @DeleteMapping("/{uploadRequestId}")
    @Operation(summary = "Отменить загрузку")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Загрузка успешно отменена"),
            @ApiResponse(responseCode = "404", description = "Запрос не найден"),
            @ApiResponse(responseCode = "403", description = "Доступ запрещён"),
            @ApiResponse(responseCode = "409", description = "Невозможно отменить в текущем статусе"),
            @ApiResponse(responseCode = "500", description = "Внутренняя ошибка сервера")
    })
    public ResponseEntity<Void> cancelUpload(
            @Parameter(description = "ID запроса на загрузку", required = true)
            @PathVariable String uploadRequestId,

            @Parameter(description = "ID клиента для проверки доступа", required = true)
            @RequestParam String clientId) {

        log.info("🗑️ DELETE /{}: cancel upload, clientId={}", uploadRequestId, clientId);

        try {
            UploadRequest uploadRequest = uploadRequestRepository.findById(uploadRequestId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Upload request not found"
                    ));

            if (!uploadRequest.getClientId().equals(clientId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            }

            if (!canCancel(uploadRequest.getStatus())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Cannot cancel upload in current state: " + uploadRequest.getStatus()
                );
            }

            uploadRequest.setStatus(UploadRequest.Status.CANCELLED);
            uploadRequest.setUpdatedAt(LocalDateTime.now());
            uploadRequestRepository.save(uploadRequest);

            log.info("✅ Upload cancelled: {}", uploadRequestId);
            return ResponseEntity.noContent().build();

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("💥 Error cancelling upload {}: {}", uploadRequestId, e.getMessage(), e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error cancelling upload: " + e.getMessage()
            );
        }
    }

    @GetMapping("/health")
    @Operation(summary = "Проверка здоровья сервиса", hidden = true)
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", LocalDateTime.now().toString());
        health.put("service", "file-uploader");

        try {
            long uploadRequests = uploadRequestRepository.count();
            long fileMetadata = fileMetadataRepository.count();

            List<UploadRequest> recentRequests = uploadRequestRepository
                    .findAll(PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "createdAt")))
                    .getContent();

            health.put("mongoDB", "OK");
            health.put("uploadRequests", uploadRequests);
            health.put("fileMetadata", fileMetadata);
            health.put("recentRequests", recentRequests.stream()
                    .map(r -> Map.of(
                            "id", r.getId(),
                            "clientId", r.getClientId(),
                            "status", r.getStatus(),
                            "createdAt", r.getCreatedAt()
                    ))
                    .collect(Collectors.toList()));

        } catch (Exception e) {
            health.put("mongoDB", "ERROR: " + e.getMessage());
            health.put("status", "DOWN");
        }

        return ResponseEntity.ok(health);
    }

    @GetMapping("/debug/all-data")
    @Operation(hidden = true)
    public ResponseEntity<Map<String, Object>> debugAllData() {
        Map<String, Object> debugInfo = new HashMap<>();

        try {
            // Все UploadRequest
            List<UploadRequest> allRequests = uploadRequestRepository.findAll();
            debugInfo.put("uploadRequests", allRequests);
            debugInfo.put("uploadRequestsCount", allRequests.size());

            // Все FileMetadata
            List<FileMetadata> allMetadata = fileMetadataRepository.findAll();
            debugInfo.put("fileMetadata", allMetadata);
            debugInfo.put("fileMetadataCount", allMetadata.size());

            // Статистика по статусам
            Map<String, Long> requestStatusCount = allRequests.stream()
                    .collect(Collectors.groupingBy(
                            r -> r.getStatus().name(),
                            Collectors.counting()
                    ));
            debugInfo.put("requestStatuses", requestStatusCount);

            Map<String, Long> metadataStatusCount = allMetadata.stream()
                    .collect(Collectors.groupingBy(
                            m -> m.getStatus().name(),
                            Collectors.counting()
                    ));
            debugInfo.put("metadataStatuses", metadataStatusCount);

            return ResponseEntity.ok(debugInfo);

        } catch (Exception e) {
            debugInfo.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(debugInfo);
        }
    }

    // ============ ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ============

    private void validateUploadParameters(String clientId, String uploadId, MultipartFile file) {
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new FileUploadException("Client ID is required");
        }

        if (clientId.length() > 100) {
            throw new FileUploadException("Client ID is too long (max 100 characters)");
        }

        if (uploadId == null || uploadId.trim().isEmpty()) {
            throw new FileUploadException("Upload ID is required");
        }

        if (uploadId.length() > 200) {
            throw new FileUploadException("Upload ID is too long (max 200 characters)");
        }

        if (file == null || file.isEmpty()) {
            throw new FileUploadException("File is required");
        }

        if (file.getOriginalFilename() == null || file.getOriginalFilename().trim().isEmpty()) {
            throw new FileUploadException("File name is required");
        }

        if (file.getSize() > 100 * 1024 * 1024) {
            throw new FileUploadException("File size exceeds maximum allowed limit of 100MB");
        }
    }

    private ResponseEntity<UploadResponseDto> handleExistingUploadRequest(UploadRequest request) {
        log.info("Handling existing request: id={}, status={}", request.getId(), request.getStatus());

        switch (request.getStatus()) {
            case COMPLETED:
                log.info("Request already completed: {}", request.getId());
                return getCompletedResponse(request);

            case PROCESSING:
            case PENDING:
                log.info("Request is still processing: {}", request.getId());
                return ResponseEntity.ok(createProcessingResponse(request));

            case FAILED:
                if (request.canRetry() && request.getAttemptCount() < 3) {
                    log.info("Retrying failed request: {}", request.getId());
                    return ResponseEntity.ok(createProcessingResponse(request));
                } else {
                    log.info("Request permanently failed: {}", request.getId());
                    return ResponseEntity.ok(createFailedResponse(request));
                }

            case CANCELLED:
                log.info("Request was cancelled: {}", request.getId());
                return ResponseEntity.ok(createCancelledResponse(request));

            default:
                log.warn("Unknown status for upload request {}: {}", request.getId(), request.getStatus());
                return ResponseEntity.ok(createProcessingResponse(request));
        }
    }

    private ResponseEntity<UploadResponseDto> getCompletedResponse(UploadRequest request) {
        if (request.getFileMetadataId() == null) {
            log.error("❌ Completed upload has no fileMetadataId: {}", request.getId());
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createFailedResponse(request));
        }

        return fileMetadataRepository.findById(request.getFileMetadataId())
                .map(metadata -> {
                    String fileUrl = metadata.getStorageInfo() != null ?
                            metadata.getStorageInfo().getUrl() : null;

                    UploadResponseDto response = UploadResponseDto.builder()
                            .status(UploadResponseDto.Status.COMPLETED)
                            .message("Upload completed successfully")
                            .uploadRequestId(request.getId())
                            .clientId(request.getClientId())
                            .uploadId(request.getUploadId())
                            .fileMetadataId(metadata.getId())
                            .fileUrl(fileUrl)
                            .originalFilename(metadata.getOriginalFilename())
                            .fileSize(metadata.getSize())
                            .contentType(metadata.getContentType())
                            .createdAt(request.getCreatedAt())
                            .build();

                    log.info("✅ Returning completed response for request: {}", request.getId());
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> {
                    log.error("❌ FileMetadata not found: {}", request.getFileMetadataId());
                    return ResponseEntity
                            .status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(createFailedResponse(request));
                });
    }

    private ResponseEntity<UploadResponseDto> handleCompletedUpload(UploadRequest uploadRequest) {
        if (uploadRequest.getFileMetadataId() == null) {
            log.error("❌ No fileMetadataId for completed upload: {}", uploadRequest.getId());
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(createFailedResponse(uploadRequest));
        }

        return fileMetadataRepository.findById(uploadRequest.getFileMetadataId())
                .map(metadata -> {
                    String fileUrl = metadata.getStorageInfo() != null ?
                            metadata.getStorageInfo().getUrl() : null;

                    UploadResponseDto response = UploadResponseDto.builder()
                            .status(UploadResponseDto.Status.COMPLETED)
                            .message("Upload completed successfully")
                            .uploadRequestId(uploadRequest.getId())
                            .clientId(uploadRequest.getClientId())
                            .uploadId(uploadRequest.getUploadId())
                            .fileMetadataId(metadata.getId())
                            .fileUrl(fileUrl)
                            .originalFilename(metadata.getOriginalFilename())
                            .fileSize(metadata.getSize())
                            .contentType(metadata.getContentType())
                            .createdAt(uploadRequest.getCreatedAt())
                            .build();

                    log.info("✅ Returning completed upload info: {}", uploadRequest.getId());
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> {
                    log.error("❌ FileMetadata not found: {}", uploadRequest.getFileMetadataId());
                    return ResponseEntity
                            .status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(createFailedResponse(uploadRequest));
                });
    }

    private UploadResponseDto createProcessingResponse(UploadRequest request) {
        UploadResponseDto response = UploadResponseDto.builder()
                .status(UploadResponseDto.Status.PROCESSING)
                .message("Upload is in progress")
                .uploadRequestId(request.getId())
                .clientId(request.getClientId())
                .uploadId(request.getUploadId())
                .originalFilename(request.getOriginalFilename())
                .fileSize(request.getFileSize())
                .contentType(request.getContentType())
                .createdAt(request.getCreatedAt())
                .build();

        log.debug("Created processing response for request: {}", request.getId());
        return response;
    }

    private UploadResponseDto createFailedResponse(UploadRequest request) {
        UploadResponseDto response = UploadResponseDto.builder()
                .status(UploadResponseDto.Status.FAILED)
                .message(request.getError() != null ? request.getError() : "Upload failed")
                .uploadRequestId(request.getId())
                .clientId(request.getClientId())
                .uploadId(request.getUploadId())
                .originalFilename(request.getOriginalFilename())
                .fileSize(request.getFileSize())
                .contentType(request.getContentType())
                .createdAt(request.getCreatedAt())
                .build();

        log.debug("Created failed response for request: {}", request.getId());
        return response;
    }

    private UploadResponseDto createCancelledResponse(UploadRequest request) {
        UploadResponseDto response = UploadResponseDto.builder()
                .status(UploadResponseDto.Status.CANCELLED)
                .message("Upload was cancelled")
                .uploadRequestId(request.getId())
                .clientId(request.getClientId())
                .uploadId(request.getUploadId())
                .originalFilename(request.getOriginalFilename())
                .fileSize(request.getFileSize())
                .contentType(request.getContentType())
                .build();

        log.debug("Created cancelled response for request: {}", request.getId());
        return response;
    }

    private boolean canCancel(UploadRequest.Status status) {
        return status == UploadRequest.Status.PENDING ||
                status == UploadRequest.Status.PROCESSING;
    }
}