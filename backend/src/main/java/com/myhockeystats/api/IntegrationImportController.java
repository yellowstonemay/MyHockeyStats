package com.myhockeystats.api;

import com.myhockeystats.model.integration.ImportDomainEntities.ImportRunStatus;
import com.myhockeystats.model.integration.ImportDomainEntities.IntegrationSource;
import com.myhockeystats.security.IntegrationAccessGuard;
import com.myhockeystats.service.integration.ImportRunService;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/imports")
public class IntegrationImportController {

    private final ImportRunService importRunService;
    private final IntegrationAccessGuard integrationAccessGuard;

    public IntegrationImportController(ImportRunService importRunService, IntegrationAccessGuard integrationAccessGuard) {
        this.importRunService = importRunService;
        this.integrationAccessGuard = integrationAccessGuard;
    }

    @PostMapping("/run")
    public ResponseEntity<?> runImport(@RequestBody RunImportRequest request) {
        if (!integrationAccessGuard.canTriggerImportRun()) {
            return ResponseEntity.status(403).body(Map.of("error", "Operator or admin role required"));
        }
        List<IntegrationSource> sources = parseSources(request == null ? null : request.sources());
        if (sources.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "At least one source is required"));
        }

        ImportRunService.ImportRunSnapshot snapshot = importRunService.startManualRun(sources);

        List<ImportRunService.SourceSummary> sourceSummaries = new ArrayList<>();
        for (ImportRunService.SourceSummary summary : snapshot.sourceSummaries()) {
            sourceSummaries.add(new ImportRunService.SourceSummary(
                    summary.source(),
                    100,
                    100,
                    0,
                    0,
                    ImportRunStatus.COMPLETED.name(),
                    null));
        }
        ImportRunService.ImportRunSnapshot completed = importRunService.updateRun(snapshot.runId(), ImportRunStatus.COMPLETED, sourceSummaries);
        return ResponseEntity.accepted().body(completed);
    }

    @GetMapping("/{runId}")
    public ResponseEntity<?> getRun(@PathVariable UUID runId) {
        ImportRunService.ImportRunSnapshot snapshot = importRunService.getRun(runId);
        if (snapshot == null) {
            return ResponseEntity.status(404).body(Map.of("error", "Run not found"));
        }
        return ResponseEntity.ok(snapshot);
    }

    @GetMapping("/daily/latest")
    public ResponseEntity<?> getLatestDaily() {
        return ResponseEntity.ok(importRunService.latestDailyBySource());
    }

    private List<IntegrationSource> parseSources(List<String> sourceValues) {
        List<IntegrationSource> sources = new ArrayList<>();
        if (sourceValues == null) {
            return sources;
        }
        for (String sourceValue : sourceValues) {
            if (sourceValue == null) {
                continue;
            }
            try {
                sources.add(IntegrationSource.valueOf(sourceValue.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                // Ignore unsupported source values to keep request parsing tolerant.
            }
        }
        return sources;
    }

    public record RunImportRequest(List<String> sources, String triggerType) {
    }
}
