package org.example.file_uploader_servise.Repository;

import org.example.file_uploader_servise.model.FileMetadata;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileMetadataRepository extends MongoRepository<FileMetadata, String> {

    Optional<FileMetadata> findByUploadRequestId(String uploadRequestId);

    Optional<FileMetadata> findByChecksumAndClientId(String checksum, String clientId);

    List<FileMetadata> findByClientId(String clientId);

    void deleteByUploadRequestId(String uploadRequestId);

    boolean existsByUploadRequestId(String uploadRequestId);

    Optional<FileMetadata> findByStorageInfo_ObjectKey(String objectKey);
}