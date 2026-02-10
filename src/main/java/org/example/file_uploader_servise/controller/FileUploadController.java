package org.example.file_uploader_servise.controller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.file_uploader_servise.Dto.UploadRequestDto;
import org.example.file_uploader_servise.Repository.FileMetadataRepository;
import org.example.file_uploader_servise.Repository.UploadRequestRepository;
import org.example.file_uploader_servise.exception.FileUploadException;
import org.example.file_uploader_servise.model.FileMetadata;
import org.example.file_uploader_servise.model.UploadRequest;
import org.example.file_uploader_servise.service.FileUploadService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

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
            description = "Асинхронная загрузка файла с идемпотентностью"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Запрос принят",
                    content = @Content(schema = @Schema(implementation = UploadRequestDto.class))),
            @ApiResponse(responseCode = "400", description = "Неверный запрос"),
            @ApiResponse(responseCode = "413", description = "Файл слишком большой"),
            @ApiResponse(responseCode = "500", description = "Ошибка сервера")
    })
    public ResponseEntity<UploadRequestDto> uploadFile(
            @RequestParam String clientId,
            @RequestParam String uploadId,
            @RequestParam MultipartFile file,
            @RequestParam(required = false) Map<String, String> metadata
    ) {

        String traceId = UUID.randomUUID().toString();
        log.info("[{}] Upload started: clientId={}, uploadId={}", traceId, clientId, uploadId);

        try {
            validateUploadParameters(clientId, uploadId, file);

            Optional<UploadRequest> existing =
                    uploadRequestRepository.findByClientIdAndUploadId(clientId, uploadId);

            if (existing.isPresent()) {
                return handleExistingRequest(existing.get());
            }

            UploadRequest request = uploadRequestRepository.save(
                    UploadRequest.builder()
                            .id(UUID.randomUUID().toString())
                            .clientId(clientId)
                            .uploadId(uploadId)
                            .originalFilename(file.getOriginalFilename())
                            .contentType(file.getContentType())
                            .fileSize(file.getSize())
                            .status(UploadRequest.Status.PENDING)
                            .attemptCount(1)
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build()
            );

            UploadRequestDto response =
                    fileUploadService.processUploadSync(clientId, uploadId, file, metadata, request.getId());

            return ResponseEntity.accepted().body(response);

        } catch (FileUploadException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (Exception e) {
            log.error("[{}] Upload failed", traceId, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Upload failed");
        }
    }


    @GetMapping("/{uploadRequestId}")
    @Operation(summary = "Получить статус загрузки")
    public ResponseEntity<UploadRequestDto> getUploadInfo(
            @PathVariable String uploadRequestId,
            @RequestParam String clientId
    ) {

        UploadRequest request = uploadRequestRepository.findById(uploadRequestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload not found"));

        if (!request.getClientId().equals(clientId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        if (request.getStatus() != UploadRequest.Status.COMPLETED) {
            return ResponseEntity.ok(buildProcessingResponse(request));
        }

        return buildCompletedResponse(request);
    }


    @DeleteMapping("/{uploadRequestId}")
    @Operation(summary = "Отменить загрузку")
    public ResponseEntity<Void> cancelUpload(
            @PathVariable String uploadRequestId,
            @RequestParam String clientId
    ) {

        UploadRequest request = uploadRequestRepository.findById(uploadRequestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Upload not found"));

        if (!request.getClientId().equals(clientId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        if (request.getStatus() != UploadRequest.Status.PENDING &&
                request.getStatus() != UploadRequest.Status.PROCESSING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot cancel in this state");
        }

        request.setStatus(UploadRequest.Status.CANCELLED);
        request.setUpdatedAt(LocalDateTime.now());
        uploadRequestRepository.save(request);

        return ResponseEntity.noContent().build();
    }


    @GetMapping("/health")
    @Operation(hidden = true)
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", LocalDateTime.now());
        health.put("uploads", uploadRequestRepository.count());
        health.put("files", fileMetadataRepository.count());
        return ResponseEntity.ok(health);
    }


    private void validateUploadParameters(String clientId, String uploadId, MultipartFile file) {
        if (clientId == null || clientId.isBlank()) {
            throw new FileUploadException("ClientId is required");
        }
        if (uploadId == null || uploadId.isBlank()) {
            throw new FileUploadException("UploadId is required");
        }
        if (file == null || file.isEmpty()) {
            throw new FileUploadException("File is required");
        }
        if (file.getSize() > 100 * 1024 * 1024) {
            throw new FileUploadException("File size exceeds 100MB");
        }
    }

    private ResponseEntity<UploadRequestDto> handleExistingRequest(UploadRequest request) {
        return switch (request.getStatus()) {
            case COMPLETED -> buildCompletedResponse(request);
            case CANCELLED -> ResponseEntity.ok(buildCancelledResponse(request));
            case FAILED -> ResponseEntity.ok(buildFailedResponse(request));
            default -> ResponseEntity.ok(buildProcessingResponse(request));
        };
    }

    private ResponseEntity<UploadRequestDto> buildCompletedResponse(UploadRequest request) {
        FileMetadata metadata = fileMetadataRepository.findById(request.getFileMetadataId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "File metadata not found"));

        return ResponseEntity.ok(
                UploadRequestDto.builder()
                        .status(UploadRequestDto.Status.COMPLETED)
                        .message("Upload completed")
                        .uploadRequestId(request.getId())
                        .clientId(request.getClientId())
                        .uploadId(request.getUploadId())
                        .fileMetadataId(metadata.getId())
                        .fileUrl(metadata.getStorageInfo().getUrl())
                        .originalFilename(metadata.getOriginalFilename())
                        .fileSize(metadata.getSize())
                        .contentType(metadata.getContentType())
                        .createdAt(request.getCreatedAt())
                        .build()
        );
    }

    private UploadRequestDto buildProcessingResponse(UploadRequest request) {
        return UploadRequestDto.builder()
                .status(UploadRequestDto.Status.PROCESSING)
                .message("Upload in progress")
                .uploadRequestId(request.getId())
                .clientId(request.getClientId())
                .uploadId(request.getUploadId())
                .originalFilename(request.getOriginalFilename())
                .fileSize(request.getFileSize())
                .contentType(request.getContentType())
                .createdAt(request.getCreatedAt())
                .build();
    }

    private UploadRequestDto buildFailedResponse(UploadRequest request) {
        return UploadRequestDto.builder()
                .status(UploadRequestDto.Status.FAILED)
                .message(request.getError() != null ? request.getError() : "Upload failed")
                .uploadRequestId(request.getId())
                .clientId(request.getClientId())
                .uploadId(request.getUploadId())
                .build();
    }

    private UploadRequestDto buildCancelledResponse(UploadRequest request) {
        return UploadRequestDto.builder()
                .status(UploadRequestDto.Status.CANCELLED)
                .message("Upload cancelled")
                .uploadRequestId(request.getId())
                .clientId(request.getClientId())
                .uploadId(request.getUploadId())
                .build();
    }
}
