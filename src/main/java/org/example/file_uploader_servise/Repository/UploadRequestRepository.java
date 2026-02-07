package org.example.file_uploader_servise.Repository;

import org.example.file_uploader_servise.model.UploadRequest;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
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

    List<UploadRequest> findByClientId(String clientId);

    List<UploadRequest> findByClientIdAndStatus(String clientId, UploadRequest.Status status);

    boolean existsByClientIdAndUploadId(String clientId, String uploadId);

    Optional<UploadRequest> findByChecksumAndClientId(String checksum, String clientId);

    @Query("{ '_id': ?0, 'status': ?1 }")
    @Update("{ '$set': { 'status': ?2, 'updatedAt': ?3 }, '$inc': { 'attemptCount': 1 } }")
    long updateStatus(String id, UploadRequest.Status currentStatus,
                      UploadRequest.Status newStatus, LocalDateTime updatedAt);

    @Query("{ '_id': ?0, 'status': 'PENDING' }")
    @Update("{ '$set': { 'status': 'PROCESSING', 'updatedAt': ?1, 'errorMessage': null }, '$inc': { 'attemptCount': 1 } }")
    long acquireForProcessing(String id, LocalDateTime updatedAt);

    @Query("{ '_id': ?0, 'status': { $in: ['PENDING', 'PROCESSING', 'FAILED'] } }")
    @Update("{ '$set': { 'status': 'CANCELLED', 'updatedAt': ?1 } }")
    long cancel(String id, LocalDateTime updatedAt);
}