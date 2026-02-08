package org.example.file_uploader_servise.Dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;
import org.example.file_uploader_servise.model.UploadRequest;

import java.time.LocalDateTime;

@Data
@Builder
@Schema(description = "Ответ на запрос статуса")
public class StatusResponseDto {

    @Schema(description = "ID запроса")
    private String uploadRequestId;

    @Schema(description = "Статус")
    private UploadRequest.Status status;

    @Schema(description = "Прогресс в процентах")
    private Integer progress;

    @Schema(description = "Сообщение о статусе")
    private String message;

    @Schema(description = "URL файла (если готово)")
    private String fileUrl;

    @Schema(description = "ID метаданных файла")
    private String fileMetadataId;

    @Schema(description = "Оригинальное имя файла")
    private String originalFilename;

    @Schema(description = "Размер файла")
    private Long fileSize;

    @Schema(description = "Количество попыток")
    private Integer attemptCount;

    @Schema(description = "Дата создания")
    private LocalDateTime createdAt;

    @Schema(description = "Дата обновления")
    private LocalDateTime updatedAt;

    @Schema(description = "Дата завершения")
    private LocalDateTime completedAt;

    public static StatusResponseDto fromUploadRequest(UploadRequest request, String fileUrl, String fileMetadataId) {
        return StatusResponseDto.builder()
                .uploadRequestId(request.getId())
                .status(request.getStatus())
                .progress(calculateProgress(request.getStatus()))
                .message(getStatusMessage(request.getStatus()))
                .fileUrl(fileUrl)
                .fileMetadataId(fileMetadataId)
                .originalFilename(request.getOriginalFilename())
                .fileSize(request.getFileSize())
                .attemptCount(request.getAttemptCount())
                .createdAt(request.getCreatedAt())
                .updatedAt(request.getUpdatedAt())
                .completedAt(request.getCompletedAt())
                .build();
    }

    private static Integer calculateProgress(UploadRequest.Status status) {
        return switch (status) {
            case PENDING -> 0;
            case PROCESSING -> 50;
            case COMPLETED -> 100;
            case FAILED, CANCELLED -> 0;
        };
    }

    private static String getStatusMessage(UploadRequest.Status status) {
        return switch (status) {
            case PENDING -> "Ожидает обработки";
            case PROCESSING -> "В процессе загрузки";
            case COMPLETED -> "Завершено успешно";
            case FAILED -> "Завершено с ошибкой";
            case CANCELLED -> "Отменено";
        };
    }
}


