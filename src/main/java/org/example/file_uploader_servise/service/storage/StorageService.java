package org.example.file_uploader_servise.service.storage;

import org.example.file_uploader_servise.model.FileMetadata;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Map;

public interface StorageService {

    FileMetadata.StorageInfo uploadFile(String bucket, String objectKey,
                                        MultipartFile file, Map<String, String> metadata);

    FileMetadata.StorageInfo uploadStream(String bucket, String objectKey,
                                          InputStream inputStream, long size,
                                          String contentType, Map<String, String> metadata);

    Resource downloadFile(String bucket, String objectKey);


    class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}