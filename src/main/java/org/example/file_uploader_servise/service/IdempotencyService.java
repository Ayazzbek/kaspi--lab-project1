package org.example.file_uploader_servise.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.file_uploader_servise.Repository.UploadRequestRepository;
import org.example.file_uploader_servise.model.UploadRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final UploadRequestRepository uploadRequestRepository;
    private final MongoTemplate mongoTemplate;

    public UploadRequest createOrGetUploadRequest(
            String clientId,
            String uploadId,
            String originalFilename,
            String contentType,
            long fileSize,
            String checksum) {

        Optional<UploadRequest> existing = uploadRequestRepository
                .findByClientIdAndUploadId(clientId, uploadId);

        if (existing.isPresent()) {
            log.debug("Found existing upload request: id={}, status={}",
                    existing.get().getId(), existing.get().getStatus());
            return existing.get();
        }

        // Создаём новый запрос
        try {
            UploadRequest newRequest = UploadRequest.create(
                    clientId, uploadId, originalFilename, contentType, fileSize, checksum
            );

            UploadRequest saved = uploadRequestRepository.save(newRequest);
            log.info("Created new upload request: id={}", saved.getId());

            return saved;

        } catch (DuplicateKeyException e) {
            // Race condition: другой поток уже создал запись
            log.warn("Race condition detected for clientId={}, uploadId={}", clientId, uploadId);

            return uploadRequestRepository.findByClientIdAndUploadId(clientId, uploadId)
                    .orElseThrow(() -> {
                        log.error("Failed to handle race condition for clientId={}, uploadId={}",
                                clientId, uploadId);
                        return new IllegalStateException("Failed to handle concurrent request creation");
                    });
        }
    }

    @Transactional
    public boolean acquireForProcessing(String uploadRequestId) {
        Query query = Query.query(
                Criteria.where("_id").is(uploadRequestId)
                        .and("status").in(UploadRequest.Status.PENDING, UploadRequest.Status.FAILED)
        );

        Update update = new Update()
                .set("status", UploadRequest.Status.PROCESSING)
                .set("updatedAt", LocalDateTime.now())
                .inc("attemptCount", 1)
                .set("errorMessage", null);

        long modified = mongoTemplate.updateFirst(query, update, UploadRequest.class)
                .getModifiedCount();

        boolean acquired = modified > 0;

        if (acquired) {
            log.debug("Successfully acquired upload request for processing: {}", uploadRequestId);
        } else {
            log.debug("Failed to acquire upload request: {}", uploadRequestId);
        }

        return acquired;
    }

    @Transactional
    public void markAsCompleted(String uploadRequestId, String fileMetadataId) {
        Query query = Query.query(
                Criteria.where("_id").is(uploadRequestId)
                        .and("status").is(UploadRequest.Status.PROCESSING)
        );

        Update update = new Update()
                .set("status", UploadRequest.Status.COMPLETED)
                .set("fileMetadataId", fileMetadataId)
                .set("completedAt", LocalDateTime.now())
                .set("updatedAt", LocalDateTime.now());

        long modified = mongoTemplate.updateFirst(query, update, UploadRequest.class)
                .getModifiedCount();

        if (modified == 0) {
            log.warn("Failed to mark request as completed: {} (not in PROCESSING state)",
                    uploadRequestId);
            throw new IllegalStateException(
                    "Cannot mark request as completed: invalid state");
        }

        log.info("Upload request marked as completed: {}, fileMetadataId={}",
                uploadRequestId, fileMetadataId);
    }

    @Transactional
    public void markAsFailed(String uploadRequestId, String errorMessage) {
        Query query = Query.query(
                Criteria.where("_id").is(uploadRequestId)
                        .and("status").is(UploadRequest.Status.PROCESSING)
        );

        Update update = new Update()
                .set("status", UploadRequest.Status.FAILED)
                .set("errorMessage", errorMessage)
                .set("updatedAt", LocalDateTime.now());

        long modified = mongoTemplate.updateFirst(query, update, UploadRequest.class)
                .getModifiedCount();

        if (modified == 0) {
            log.warn("Failed to mark request as failed: {} (not in PROCESSING state)",
                    uploadRequestId);
        } else {
            log.info("Upload request marked as failed: {}, error: {}",
                    uploadRequestId, errorMessage);
        }
    }

    @Transactional
    public boolean cancel(String uploadRequestId) {
        long modified = uploadRequestRepository.cancel(
                uploadRequestId, LocalDateTime.now()
        );

        boolean cancelled = modified > 0;

        if (cancelled) {
            log.info("Upload request cancelled: {}", uploadRequestId);
        } else {
            log.debug("Failed to cancel upload request: {} (already completed or cancelled)",
                    uploadRequestId);
        }

        return cancelled;
    }

    public Optional<String> findDuplicateByContent(String clientId, String checksum) {
        return uploadRequestRepository.findByChecksumAndClientId(checksum, clientId)
                .filter(req -> req.getStatus() == UploadRequest.Status.COMPLETED)
                .map(UploadRequest::getFileMetadataId);
    }
}