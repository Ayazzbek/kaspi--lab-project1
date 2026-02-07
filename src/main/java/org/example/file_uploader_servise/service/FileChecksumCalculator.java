package org.example.file_uploader_servise.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

@Slf4j
@Component
public class FileChecksumCalculator {

    public String calculateSha256(MultipartFile file) throws IOException {
        try (InputStream inputStream = new BufferedInputStream(file.getInputStream())) {
            return DigestUtils.sha256Hex(inputStream);
        } catch (IOException e) {
            log.error("Failed to calculate checksum for file: {}", file.getOriginalFilename(), e);
            throw new IOException("Failed to calculate file checksum", e);
        }
    }
}