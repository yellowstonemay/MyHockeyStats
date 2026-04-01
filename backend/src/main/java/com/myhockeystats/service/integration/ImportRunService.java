package com.myhockeystats.service.integration;

import com.myhockeystats.model.integration.ImportDomainEntities.ImportRunStatus;
import com.myhockeystats.model.integration.ImportDomainEntities.IntegrationSource;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class ImportRunService {

    private final Map<UUID, ImportRunSnapshot> runs = new ConcurrentHashMap<>();
    private final IntegrationDataService integrationDataService;

    public ImportRunService(IntegrationDataService integrationDataService) {
        this.integrationDataService = integrationDataService;
    }

    public ImportRunSnapshot startManualRun(List<IntegrationSource> sources) {
        UUID runId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        List<SourceSummary> summaries = new ArrayList<>();
        for (IntegrationSource source : sources) {
            summaries.add(new SourceSummary(source.name(), 0, 0, 0, 0, ImportRunStatus.RUNNING.name(), null));
        }

        ImportRunSnapshot snapshot = new ImportRunSnapshot(runId, ImportRunStatus.RUNNING.name(), now, null, summaries);
        runs.put(runId, snapshot);
        return snapshot;
    }

    public ImportRunSnapshot updateRun(UUID runId, ImportRunStatus status, List<SourceSummary> sourceSummaries) {
        ImportRunSnapshot existing = runs.get(runId);
        if (existing == null) {
            return null;
        }
        ImportRunSnapshot updated = new ImportRunSnapshot(
                existing.runId(),
                status.name(),
                existing.startedAt(),
                OffsetDateTime.now(),
                sourceSummaries);
        runs.put(runId, updated);
        return updated;
    }

    public ImportRunSnapshot getRun(UUID runId) {
        ImportRunSnapshot snapshot = runs.get(runId);
        if (snapshot != null) {
            return snapshot;
        }
        return integrationDataService.findImportRun(runId)
                .map(run -> new ImportRunSnapshot(
                        run.runId(),
                        run.status(),
                        run.startedAt(),
                        run.endedAt(),
                        List.of(new SourceSummary(
                                run.source(),
                                run.processed(),
                                run.accepted(),
                                run.rejected(),
                                run.duplicateSkipped(),
                                run.status(),
                                run.errorSummary()))))
                .orElse(null);
    }

    public DailyLatestSnapshot latestDailyBySource() {
        Map<String, DailySourceStatus> latest = new LinkedHashMap<>();
        for (IntegrationSource source : IntegrationSource.values()) {
            var latestRun = integrationDataService.findLatestImportRunBySource(source.name());
            int trackedPlayers = integrationDataService.countTrackedPlayersBySource(source.name());
            if (latestRun.isPresent()) {
                var run = latestRun.get();
                latest.put(source.name(), new DailySourceStatus(
                        source.name(),
                        run.runId(),
                        run.status(),
                        run.endedAt(),
                        run.processed(),
                        run.accepted(),
                        run.rejected(),
                        run.duplicateSkipped(),
                        trackedPlayers));
            } else {
                latest.put(source.name(), new DailySourceStatus(source.name(), null, "NOT_RUN", null, 0, 0, 0, 0, trackedPlayers));
            }
        }
        return new DailyLatestSnapshot("daily", new ArrayList<>(latest.values()));
    }

    public record SourceSummary(
            String source,
            int processed,
            int accepted,
            int rejected,
            int duplicateSkipped,
            String status,
            String errorSummary) {
    }

    public record ImportRunSnapshot(
            UUID runId,
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            List<SourceSummary> sourceSummaries) {
    }

        public record DailySourceStatus(
            String source,
            UUID runId,
            String status,
            OffsetDateTime endedAt,
            int processed,
            int accepted,
            int rejected,
            int duplicateSkipped,
            int trackedPlayers) {
    }

    public record DailyLatestSnapshot(String schedule, List<DailySourceStatus> latestBySource) {
    }
}
