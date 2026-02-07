package org.example.file_uploader_servise.Dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Стандартизированный ответ об ошибке")
public class ErrorResponseDto {

    @Schema(description = "Временная метка возникновения ошибки")
    private LocalDateTime timestamp;

    @Schema(description = "HTTP статус код")
    private Integer status;

    @Schema(description = "Текстовое описание HTTP статуса")
    private String error;

    @Schema(description = "Код ошибки")
    private String code;

    @Schema(description = "Сообщение об ошибке")
    private String message;

    @Schema(description = "Детальное описание ошибки")
    private String details;

    @Schema(description = "Путь к ресурсу")
    private String path;

    public static ErrorResponseDto badRequest(String message, String path) {
        return ErrorResponseDto.builder()
                .timestamp(LocalDateTime.now())
                .status(400)
                .error("Bad Request")
                .code("BAD_REQUEST")
                .message(message)
                .path(path)
                .build();
    }

    public static ErrorResponseDto notFound(String message, String path) {
        return ErrorResponseDto.builder()
                .timestamp(LocalDateTime.now())
                .status(404)
                .error("Not Found")
                .code("NOT_FOUND")
                .message(message)
                .path(path)
                .build();
    }

    public static ErrorResponseDto forbidden(String message, String path) {
        return ErrorResponseDto.builder()
                .timestamp(LocalDateTime.now())
                .status(403)
                .error("Forbidden")
                .code("FORBIDDEN")
                .message(message)
                .path(path)
                .build();
    }

    public static ErrorResponseDto conflict(String message, String path) {
        return ErrorResponseDto.builder()
                .timestamp(LocalDateTime.now())
                .status(409)
                .error("Conflict")
                .code("CONFLICT")
                .message(message)
                .path(path)
                .build();
    }

    public static ErrorResponseDto internalError(String message, String path) {
        return ErrorResponseDto.builder()
                .timestamp(LocalDateTime.now())
                .status(500)
                .error("Internal Server Error")
                .code("INTERNAL_ERROR")
                .message(message)
                .path(path)
                .build();
    }

    public static ErrorResponseDto fileTooLarge(String message, String path) {
        return ErrorResponseDto.builder()
                .timestamp(LocalDateTime.now())
                .status(413)
                .error("Payload Too Large")
                .code("FILE_TOO_LARGE")
                .message(message)
                .path(path)
                .build();
    }
}