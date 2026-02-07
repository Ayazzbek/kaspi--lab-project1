package org.example.file_uploader_servise.Dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Data
@Schema(description = "Запрос на загрузку файла")
public class UploadRequestDto {

    @NotBlank(message = "Client ID обязательно")
    @Size(min = 1, max = 100, message = "Client ID должен быть от 1 до 100 символов")
    @Schema(description = "Идентификатор клиента", example = "client-123", required = true)
    private String clientId;

    @NotBlank(message = "Upload ID обязательно")
    @Size(min = 1, max = 200, message = "Upload ID должен быть от 1 до 200 символов")
    @Schema(description = "Уникальный идентификатор загрузки", example = "upload-abc-123", required = true)
    private String uploadId;

    @NotNull(message = "Файл обязателен")
    @Schema(description = "Файл для загрузки", required = true)
    private MultipartFile file;

    @Schema(description = "Дополнительные метаданные")
    private Map<String, String> metadata;

    @Schema(description = "Таймаут обработки в секундах", example = "300")
    private Integer timeoutSeconds;
}