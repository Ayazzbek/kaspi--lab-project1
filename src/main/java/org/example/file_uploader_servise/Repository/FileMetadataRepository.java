package org.example.file_uploader_servise.Repository;

import org.example.file_uploader_servise.model.FileMetadata;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;


@Repository
public interface FileMetadataRepository extends MongoRepository<FileMetadata, String> {

}