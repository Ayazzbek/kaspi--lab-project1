package org.example.file_uploader_servise.service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.file_uploader_servise.Repository.UploadRequestRepository;
import org.example.file_uploader_servise.model.UploadRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadCleanupService {

    private final UploadRequestRepository uploadRequestRepository;

    @Value("${app.upload.cleanup.stalled-threshold-seconds:1800}")
    private int stalledThresholdSeconds;

    @Value("${app.upload.cleanup.enabled:true}")
    private boolean cleanupEnabled;

    @Scheduled(fixedDelayString = "${app.upload.cleanup.interval-seconds:30000}")
    @Transactional
    public void cleanupStalledUploads() {
        if (!cleanupEnabled) {
            return;
        }

        log.debug("Starting stalled uploads cleanup");

        LocalDateTime threshold = LocalDateTime.now()
                .minusSeconds(stalledThresholdSeconds);

        List<UploadRequest> stalledUploads = uploadRequestRepository
                .findByStatusAndUpdatedAtBefore(UploadRequest.Status.PROCESSING, threshold);

        int cleanedCount = 0;

        for (UploadRequest stalled : stalledUploads) {
            try {
                log.warn("Found stalled upload: id={}, clientId={}, lastUpdate={}, attemptCount={}",
                        stalled.getId(), stalled.getClientId(), stalled.getUpdatedAt(),
                        stalled.getAttemptCount());

                stalled.markFailed("Operation stalled - timeout after " + stalledThresholdSeconds + " seconds");
                uploadRequestRepository.save(stalled);
                cleanedCount++;

            } catch (Exception e) {
                log.error("Failed to cleanup stalled upload {}: {}",
                        stalled.getId(), e.getMessage(), e);
            }
        }

        if (cleanedCount > 0) {
            log.info("Cleaned up {} stalled uploads", cleanedCount);
        }
    }

    @Scheduled(cron = "${app.upload.cleanup.old-records-cron:0 0 3 * * ?}")
    @Transactional
    public void cleanupOldRecords() {
        if (!cleanupEnabled) {
            return;
        }

        log.debug("Starting old records cleanup");

        LocalDateTime threshold = LocalDateTime.now().minusDays(30);

        List<UploadRequest> oldRequests = uploadRequestRepository
                .findByStatusAndUpdatedAtBefore(UploadRequest.Status.COMPLETED, threshold);

        oldRequests.addAll(uploadRequestRepository
                .findByStatusAndUpdatedAtBefore(UploadRequest.Status.FAILED, threshold));

        int deletedCount = 0;
        for (UploadRequest oldRequest : oldRequests) {
            try {
                log.info("Removing old upload record: id={}, status={}, updatedAt={}",
                        oldRequest.getId(), oldRequest.getStatus(), oldRequest.getUpdatedAt());

                uploadRequestRepository.delete(oldRequest);
                deletedCount++;

            } catch (Exception e) {
                log.error("Failed to cleanup old record {}: {}",
                        oldRequest.getId(), e.getMessage(), e);
            }
        }

        if (deletedCount > 0) {
            log.info("Deleted {} old records", deletedCount);
        }
    }

    @Scheduled(cron = "0 0 4 * * ?")
    public void cleanupTemporaryFiles() {
        if (!cleanupEnabled) {
            return;
        }
        log.debug("Starting temporary files cleanup");
    }
}