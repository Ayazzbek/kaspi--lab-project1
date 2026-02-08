package org.example.file_uploader_servise.Repository;
import org.example.file_uploader_servise.model.UploadRequest;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UploadRequestRepository extends MongoRepository<UploadRequest, String> {

    Optional<UploadRequest> findByClientIdAndUploadId(String clientId, String uploadId);

    List<UploadRequest> findByStatusAndUpdatedAtBefore(
            UploadRequest.Status status,
            LocalDateTime updatedAt
    );

    List<UploadRequest> findByStatus(UploadRequest.Status status);

}