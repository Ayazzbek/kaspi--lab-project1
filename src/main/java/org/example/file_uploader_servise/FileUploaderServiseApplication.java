package org.example.file_uploader_servise;

import org.example.file_uploader_servise.Repository.UploadRequestRepository;
import org.example.file_uploader_servise.model.UploadRequest;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class FileUploaderServiseApplication {

    public static void main(String[] args) {
        SpringApplication.run(FileUploaderServiseApplication.class, args);
    }


}


