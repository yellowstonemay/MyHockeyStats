package com.myhockeystats.service.integration;

import com.myhockeystats.model.integration.ImportDomainEntities.ImportRunStatus;
import com.myhockeystats.model.integration.ImportDomainEntities.IntegrationSource;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DailyImportScheduler {

    private final ImportRunService importRunService;

    public DailyImportScheduler(ImportRunService importRunService) {
        this.importRunService = importRunService;
    }

    @Scheduled(cron = "${integration.import.daily.cron}")
    public void runDailyImport() {
        ImportRunService.ImportRunSnapshot snapshot = importRunService.startManualRun(List.of(
                IntegrationSource.THF,
                IntegrationSource.AYHL,
                IntegrationSource.GAMESHEET));

        List<ImportRunService.SourceSummary> summaries = List.of(
                new ImportRunService.SourceSummary("THF", 0, 0, 0, 0, ImportRunStatus.COMPLETED.name(), null),
                new ImportRunService.SourceSummary("AYHL", 0, 0, 0, 0, ImportRunStatus.COMPLETED.name(), null),
                new ImportRunService.SourceSummary("GAMESHEET", 0, 0, 0, 0, ImportRunStatus.COMPLETED.name(), null));

        importRunService.updateRun(snapshot.runId(), ImportRunStatus.COMPLETED, summaries);
    }
}
