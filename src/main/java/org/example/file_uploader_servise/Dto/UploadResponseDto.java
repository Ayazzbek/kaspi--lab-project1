package org.example.file_uploader_servise.Dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.file_uploader_servise.model.UploadRequest;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Ответ на запрос загрузки")
public class UploadResponseDto {

    @Schema(description = "Статус обработки")
    private Status status;

    @Schema(description = "Сообщение")
    private String message;

    @Schema(description = "ID запроса на загрузку")
    private String uploadRequestId;

    @Schema(description = "ID метаданных файла")
    private String fileMetadataId;

    @Schema(description = "URL для скачивания файла")
    private String fileUrl;

    @Schema(description = "Оригинальное имя файла")
    private String originalFilename;

    @Schema(description = "Размер файла в байтах")
    private Long fileSize;

    @Schema(description = "Хеш-сумма файла (SHA-256)")
    private String checksum;

    @Schema(description = "Дата создания")
    private LocalDateTime createdAt;

    @Schema(description = "Дата обновления")
    private LocalDateTime updatedAt;

    @Schema(description = "Дата завершения")
    private LocalDateTime completedAt;  // <-- ДОБАВЬТЕ ЭТО ПОЛЕ

    @Schema(description = "Информация об ошибке")
    private ErrorInfo error;

    @Schema(description = "Количество попыток")
    private Integer attemptCount;

    public enum Status {
        ACCEPTED,
        PROCESSING,
        COMPLETED,
        FAILED,
        DUPLICATE,
        VALIDATION_ERROR,
        CANCELLED
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Информация об ошибке")
    public static class ErrorInfo {
        private String code;
        private String message;
        private String details;
        private LocalDateTime occurredAt;
    }

    public static UploadResponseDto accepted(UploadRequest request) {
        return UploadResponseDto.builder()
                .status(Status.ACCEPTED)
                .message("Запрос принят в обработку")
                .uploadRequestId(request.getId())
                .originalFilename(request.getOriginalFilename())
                .fileSize(request.getFileSize())
                .checksum(request.getChecksum())
                .createdAt(request.getCreatedAt())
                .build();
    }

    public static UploadResponseDto processing(UploadRequest request) {
        return UploadResponseDto.builder()
                .status(Status.PROCESSING)
                .message("Файл загружается")
                .uploadRequestId(request.getId())
                .originalFilename(request.getOriginalFilename())
                .fileSize(request.getFileSize())
                .checksum(request.getChecksum())
                .createdAt(request.getCreatedAt())
                .updatedAt(request.getUpdatedAt())
                .attemptCount(request.getAttemptCount())
                .build();
    }

    public static UploadResponseDto completed(UploadRequest request,
                                              String fileMetadataId,
                                              String fileUrl) {
        return UploadResponseDto.builder()
                .status(Status.COMPLETED)
                .message("Файл успешно загружен")
                .uploadRequestId(request.getId())
                .fileMetadataId(fileMetadataId)
                .fileUrl(fileUrl)
                .originalFilename(request.getOriginalFilename())
                .fileSize(request.getFileSize())
                .checksum(request.getChecksum())
                .createdAt(request.getCreatedAt())
                .updatedAt(request.getUpdatedAt())
                .completedAt(request.getCompletedAt())  // <-- ТЕПЕРЬ РАБОТАЕТ
                .attemptCount(request.getAttemptCount())
                .build();
    }

    public static UploadResponseDto failed(UploadRequest request) {
        return UploadResponseDto.builder()
                .status(Status.FAILED)
                .message("Ошибка при загрузке файла")
                .uploadRequestId(request.getId())
                .originalFilename(request.getOriginalFilename())
                .checksum(request.getChecksum())
                .createdAt(request.getCreatedAt())
                .updatedAt(request.getUpdatedAt())
                .attemptCount(request.getAttemptCount())
                .error(ErrorInfo.builder()
                        .code("UPLOAD_ERROR")
                        .message(request.getErrorMessage())
                        .occurredAt(request.getUpdatedAt())
                        .build())
                .build();
    }

    public static UploadResponseDto duplicate(UploadRequest existingRequest, String fileUrl) {
        return UploadResponseDto.builder()
                .status(Status.DUPLICATE)
                .message("Дублирующий запрос (идемпотентность)")
                .uploadRequestId(existingRequest.getId())
                .fileMetadataId(existingRequest.getFileMetadataId())
                .fileUrl(fileUrl)
                .originalFilename(existingRequest.getOriginalFilename())
                .fileSize(existingRequest.getFileSize())
                .checksum(existingRequest.getChecksum())
                .createdAt(existingRequest.getCreatedAt())
                .updatedAt(existingRequest.getUpdatedAt())
                .completedAt(existingRequest.getCompletedAt())
                .build();
    }
}