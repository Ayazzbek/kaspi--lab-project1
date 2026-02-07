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
import org.example.file_uploader_servise.service.IdempotencyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Slf4j
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Validated
@Tag(name = "File Upload", description = "API для асинхронной загрузки файлов")
public class FileUploadController {

    private final FileUploadService fileUploadService;
    private final IdempotencyService idempotencyService;
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
                    responseCode = "409",
                    description = "Конфликт: запрос уже обрабатывается"
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

            @Parameter(description = "Таймаут обработки в секундах (по умолчанию: 300)")
            @RequestParam(value = "timeoutSeconds", required = false, defaultValue = "300") Integer timeoutSeconds,

            @Parameter(description = "Дополнительные метаданные")
            @RequestParam(value = "metadata", required = false) Map<String, String> metadata) {

        log.info("Received upload request: clientId={}, uploadId={}, filename={}, size={}",
                clientId, uploadId, file.getOriginalFilename(), file.getSize());

        try {
            // 1. Валидация базовых параметров
            validateUploadParameters(clientId, uploadId, file);

            // 2. Проверяем существующий запрос (идемпотентность)
            Optional<UploadRequest> existingRequest = uploadRequestRepository
                    .findByClientIdAndUploadId(clientId, uploadId);

            if (existingRequest.isPresent()) {
                log.debug("Found existing upload request: id={}, status={}",
                        existingRequest.get().getId(), existingRequest.get().getStatus());
                return handleExistingUploadRequest(existingRequest.get());
            }

            // 3. Запускаем асинхронную обработку
            fileUploadService.processUpload(clientId, uploadId, file, metadata, timeoutSeconds);

            // 4. Создаём немедленный ответ
            UploadResponseDto immediateResponse = UploadResponseDto.accepted(
                    UploadRequest.builder()
                            .clientId(clientId)
                            .uploadId(uploadId)
                            .originalFilename(file.getOriginalFilename())
                            .contentType(file.getContentType())
                            .fileSize(file.getSize())
                            .status(UploadRequest.Status.PENDING)
                            .build()
            );

            log.info("Upload request accepted for processing: clientId={}, uploadId={}", clientId, uploadId);

            return ResponseEntity
                    .status(HttpStatus.ACCEPTED)
                    .body(immediateResponse);

        } catch (FileUploadException e) {
            log.warn("Upload validation failed: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);

        } catch (Exception e) {
            log.error("Unexpected error during upload: {}", e.getMessage(), e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Internal server error: " + e.getMessage(),
                    e
            );
        }
    }

    @GetMapping("/{uploadRequestId}/status")
    @Operation(summary = "Получить статус загрузки")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Статус получен"),
            @ApiResponse(responseCode = "404", description = "Запрос не найден"),
            @ApiResponse(responseCode = "403", description = "Доступ запрещён"),
            @ApiResponse(responseCode = "500", description = "Внутренняя ошибка сервера")
    })
    public ResponseEntity<StatusResponseDto> getUploadStatus(
            @Parameter(description = "ID запроса на загрузку", required = true)
            @PathVariable String uploadRequestId,

            @Parameter(description = "ID клиента для проверки доступа", required = true)
            @RequestParam String clientId) {

        log.debug("Status check: requestId={}, clientId={}", uploadRequestId, clientId);

        try {
            UploadRequest uploadRequest = uploadRequestRepository.findById(uploadRequestId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "Upload request not found: " + uploadRequestId
                    ));

            // Проверка прав доступа
            if (!uploadRequest.getClientId().equals(clientId)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Access denied"
                );
            }

            // Получение информации о файле
            String fileUrl = null;
            String fileMetadataId = null;
            Optional<FileMetadata> fileMetadata = fileMetadataRepository
                    .findByUploadRequestId(uploadRequestId);

            if (fileMetadata.isPresent() && fileMetadata.get().getStorageInfo() != null) {
                fileUrl = fileMetadata.get().getStorageInfo().getUrl();
                fileMetadataId = fileMetadata.get().getId();
            }

            StatusResponseDto response = StatusResponseDto.fromUploadRequest(
                    uploadRequest, fileUrl, fileMetadataId
            );

            return ResponseEntity.ok(response);

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error checking upload status", e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error checking upload status: " + e.getMessage()
            );
        }
    }

    @GetMapping("/{uploadRequestId}")
    @Operation(summary = "Получить информацию о загрузке")
    public ResponseEntity<UploadResponseDto> getUploadInfo(
            @Parameter(description = "ID запроса на загрузку", required = true)
            @PathVariable String uploadRequestId,

            @Parameter(description = "ID клиента для проверки доступа", required = true)
            @RequestParam String clientId) {

        log.debug("Get upload info: requestId={}, clientId={}", uploadRequestId, clientId);

        try {
            UploadRequest uploadRequest = uploadRequestRepository.findById(uploadRequestId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Upload request not found"
                    ));

            if (!uploadRequest.getClientId().equals(clientId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            }

            // Если загрузка не завершена
            if (uploadRequest.getStatus() != UploadRequest.Status.COMPLETED) {
                return ResponseEntity.ok(UploadResponseDto.processing(uploadRequest));
            }

            // Для завершённой загрузки
            return fileMetadataRepository.findByUploadRequestId(uploadRequestId)
                    .map(metadata -> {
                        String fileUrl = metadata.getStorageInfo() != null ?
                                metadata.getStorageInfo().getUrl() : null;
                        return ResponseEntity.ok(
                                UploadResponseDto.completed(
                                        uploadRequest,
                                        metadata.getId(),
                                        fileUrl
                                )
                        );
                    })
                    .orElseGet(() -> ResponseEntity
                            .status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(UploadResponseDto.failed(uploadRequest)));

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting upload info", e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error getting upload information"
            );
        }
    }

    @DeleteMapping("/{uploadRequestId}")
    @Operation(summary = "Отменить загрузку")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Загрузка успешно отменена"),
            @ApiResponse(responseCode = "404", description = "Запрос не найден"),
            @ApiResponse(responseCode = "403", description = "Доступ запрещён"),
            @ApiResponse(responseCode = "409", description = "Невозможно отменить в текущем статусе")
    })
    public ResponseEntity<Void> cancelUpload(
            @Parameter(description = "ID запроса на загрузку", required = true)
            @PathVariable String uploadRequestId,

            @Parameter(description = "ID клиента для проверки доступа", required = true)
            @RequestParam String clientId) {

        log.info("Cancel upload request: requestId={}, clientId={}", uploadRequestId, clientId);

        try {
            UploadRequest uploadRequest = uploadRequestRepository.findById(uploadRequestId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "Upload request not found"
                    ));

            if (!uploadRequest.getClientId().equals(clientId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            }

            boolean cancelled = idempotencyService.cancel(uploadRequestId);

            if (!cancelled) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Cannot cancel upload in current state"
                );
            }

            return ResponseEntity.noContent().build();

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error cancelling upload", e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error cancelling upload"
            );
        }
    }

    @GetMapping("/health")
    @Operation(summary = "Проверка здоровья сервиса", hidden = true)
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
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

        // Проверка размера файла
        if (file.getSize() > 100 * 1024 * 1024) { // 100MB
            throw new FileUploadException("File size exceeds maximum allowed limit of 100MB");
        }
    }

    private ResponseEntity<UploadResponseDto> handleExistingUploadRequest(UploadRequest request) {
        switch (request.getStatus()) {
            case COMPLETED:
                return getCompletedResponse(request);

            case PROCESSING:
                return ResponseEntity.ok(UploadResponseDto.processing(request));

            case FAILED:
                if (request.canRetry()) {
                    return ResponseEntity.ok(UploadResponseDto.processing(request));
                } else {
                    return ResponseEntity.ok(UploadResponseDto.failed(request));
                }

            case PENDING:
                return ResponseEntity.ok(UploadResponseDto.accepted(request));

            case CANCELLED:
                return ResponseEntity.ok(UploadResponseDto.builder()
                        .status(UploadResponseDto.Status.CANCELLED)
                        .message("Upload was cancelled")
                        .uploadRequestId(request.getId())
                        .build());

            default:
                return ResponseEntity.ok(UploadResponseDto.accepted(request));
        }
    }

    private ResponseEntity<UploadResponseDto> getCompletedResponse(UploadRequest request) {
        return fileMetadataRepository.findByUploadRequestId(request.getId())
                .map(metadata -> {
                    String fileUrl = metadata.getStorageInfo() != null ?
                            metadata.getStorageInfo().getUrl() : null;
                    return ResponseEntity.ok(
                            UploadResponseDto.completed(
                                    request,
                                    metadata.getId(),
                                    fileUrl
                            )
                    );
                })
                .orElseGet(() -> ResponseEntity
                        .status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(UploadResponseDto.failed(request)));
    }
}