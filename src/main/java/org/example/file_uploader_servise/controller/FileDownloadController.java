package org.example.file_uploader_servise.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.file_uploader_servise.Repository.FileMetadataRepository;
import org.example.file_uploader_servise.model.FileMetadata;
import org.example.file_uploader_servise.service.storage.StorageService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Tag(name = "File Download", description = "API для скачивания файлов")
public class FileDownloadController {

    private final FileMetadataRepository fileMetadataRepository;
    private final StorageService storageService;

    @GetMapping("/{fileMetadataId}/download")
    @Operation(
            summary = "Скачать файл",
            description = "Скачивание файла по его ID метаданных"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Файл успешно скачан"),
            @ApiResponse(responseCode = "400", description = "Неверный запрос"),
            @ApiResponse(responseCode = "403", description = "Доступ запрещён"),
            @ApiResponse(responseCode = "404", description = "Файл не найден"),
            @ApiResponse(responseCode = "410", description = "Файл был удалён"),
            @ApiResponse(responseCode = "500", description = "Ошибка при скачивании файла")
    })
    public ResponseEntity<Resource> downloadFile(
            @Parameter(description = "ID метаданных файла", required = true)
            @PathVariable String fileMetadataId,

            @Parameter(description = "ID клиента для проверки доступа", required = true)
            @RequestParam String clientId) {

        log.info("Download request: fileId={}, clientId={}", fileMetadataId, clientId);

        try {
            FileMetadata fileMetadata = fileMetadataRepository.findById(fileMetadataId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "File not found"
                    ));

            // Проверка прав доступа
            if (!fileMetadata.getClientId().equals(clientId)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Access denied"
                );
            }

            // Проверяем информацию о хранилище
            if (fileMetadata.getStorageInfo() == null) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "File storage information is missing"
                );
            }

            // Скачиваем файл
            Resource resource = storageService.downloadFile(
                    fileMetadata.getStorageInfo().getBucket(),
                    fileMetadata.getStorageInfo().getObjectKey()
            );

            if (resource == null || !resource.exists()) {
                throw new ResponseStatusException(
                        HttpStatus.GONE,
                        "File has been deleted"
                );
            }

            // Настраиваем заголовки
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(
                    fileMetadata.getContentType() != null ?
                            fileMetadata.getContentType() :
                            MediaType.APPLICATION_OCTET_STREAM_VALUE
            ));

            String filename = fileMetadata.getOriginalFilename() != null ?
                    fileMetadata.getOriginalFilename() : "file";
            headers.setContentDispositionFormData("attachment", filename);
            headers.setContentLength(fileMetadata.getSize());

            log.info("File download successful: fileId={}, filename={}", fileMetadataId, filename);
            return ResponseEntity.ok().headers(headers).body(resource);

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error downloading file", e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to download file"
            );
        }
    }

    @GetMapping("/{fileMetadataId}/url")
    @Operation(
            summary = "Получить временную ссылку для скачивания",
            description = "Генерация временной ссылки для скачивания файла"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "URL успешно сгенерирован"),
            @ApiResponse(responseCode = "400", description = "Неверный запрос"),
            @ApiResponse(responseCode = "403", description = "Доступ запрещён"),
            @ApiResponse(responseCode = "404", description = "Файл не найден"),
            @ApiResponse(responseCode = "500", description = "Ошибка при генерации URL")
    })
    public ResponseEntity<Map<String, Object>> getDownloadUrl(
            @Parameter(description = "ID метаданных файла", required = true)
            @PathVariable String fileMetadataId,

            @Parameter(description = "ID клиента для проверки доступа", required = true)
            @RequestParam String clientId,

            @Parameter(description = "Срок действия ссылки в минуты", example = "60")
            @RequestParam(defaultValue = "60") int expirationMinutes) {

        log.info("Download URL request: fileId={}, clientId={}", fileMetadataId, clientId);

        try {
            // Валидация
            if (expirationMinutes <= 0 || expirationMinutes > 1440) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Expiration minutes must be between 1 and 1440"
                );
            }

            FileMetadata fileMetadata = fileMetadataRepository.findById(fileMetadataId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND,
                            "File not found"
                    ));

            if (!fileMetadata.getClientId().equals(clientId)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Access denied"
                );
            }

            if (fileMetadata.getStorageInfo() == null) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "File storage information is missing"
                );
            }

            // Генерируем URL
            String downloadUrl = storageService.getPresignedUrl(
                    fileMetadata.getStorageInfo().getBucket(),
                    fileMetadata.getStorageInfo().getObjectKey(),
                    expirationMinutes
            );

            if (downloadUrl == null || downloadUrl.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Failed to generate download URL"
                );
            }

            // Возвращаем простой JSON
            Map<String, Object> response = new HashMap<>();
            response.put("url", downloadUrl);
            response.put("expiresInMinutes", expirationMinutes);
            response.put("expiresAt", LocalDateTime.now().plusMinutes(expirationMinutes).toString());
            response.put("fileId", fileMetadataId);
            response.put("fileName", fileMetadata.getOriginalFilename());

            return ResponseEntity.ok(response);

        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error generating download URL", e);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to generate download URL"
            );
        }
    }

    @GetMapping("/{fileMetadataId}/info")
    @Operation(summary = "Получить информацию о файле")
    public ResponseEntity<FileMetadata> getFileInfo(
            @PathVariable String fileMetadataId,
            @RequestParam String clientId) {

        log.debug("File info request: fileId={}, clientId={}", fileMetadataId, clientId);

        FileMetadata fileMetadata = fileMetadataRepository.findById(fileMetadataId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "File not found"
                ));

        if (!fileMetadata.getClientId().equals(clientId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Access denied"
            );
        }

        return ResponseEntity.ok(fileMetadata);
    }
}