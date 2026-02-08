package org.example.file_uploader_servise.Dto;
import lombok.*;
import org.example.file_uploader_servise.model.UploadRequest;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadResponseDto {

    private Status status;
    private String message;
    private String uploadRequestId;
    private String fileMetadataId;
    private String fileUrl;
    private String clientId;
    private String uploadId;
    private String originalFilename;
    private Long fileSize;
    private String contentType;
    private LocalDateTime createdAt;

    public enum Status {
        PROCESSING, COMPLETED, FAILED, CANCELLED
    }

    public static UploadResponseDto processing(UploadRequest request) {
        return UploadResponseDto.builder()
                .status(Status.PROCESSING)
                .uploadRequestId(request.getId())
                .clientId(request.getClientId())
                .uploadId(request.getUploadId())
                .originalFilename(request.getOriginalFilename())
                .fileSize(request.getFileSize())
                .message("Upload is in progress")
                .build();
    }

    public static UploadResponseDto completed(UploadRequest request, String fileMetadataId, String fileUrl) {
        return UploadResponseDto.builder()
                .status(Status.COMPLETED)
                .uploadRequestId(request.getId())
                .fileMetadataId(fileMetadataId)
                .fileUrl(fileUrl)
                .clientId(request.getClientId())
                .uploadId(request.getUploadId())
                .originalFilename(request.getOriginalFilename())
                .fileSize(request.getFileSize())
                .contentType(request.getContentType())
                .message("Upload completed successfully")
                .build();
    }

    public static UploadResponseDto failed(UploadRequest request) {
        return UploadResponseDto.builder()
                .status(Status.FAILED)
                .uploadRequestId(request.getId())
                .clientId(request.getClientId())
                .uploadId(request.getUploadId())
                .originalFilename(request.getOriginalFilename())
                .fileSize(request.getFileSize())
                .message("Upload failed" + (request.getError() != null ? ": " + request.getError() : ""))
                .build();
    }
}