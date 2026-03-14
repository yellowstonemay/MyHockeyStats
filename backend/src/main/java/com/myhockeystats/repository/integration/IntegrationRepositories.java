package com.myhockeystats.repository.integration;

import com.myhockeystats.model.integration.ImportDomainEntities.ImportedPlayerIdentity;
import com.myhockeystats.model.integration.ImportDomainEntities.ImportRunSummary;
import com.myhockeystats.model.integration.ImportDomainEntities.SourceConflictSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface IntegrationRepositories {
    interface ImportRunRepository {
        ImportRunSummary save(ImportRunSummary runSummary);

        Optional<ImportRunSummary> findById(UUID runId);
    }

    interface ImportedPlayerRepository {
        ImportedPlayerIdentity save(ImportedPlayerIdentity importedPlayerIdentity);

        List<ImportedPlayerIdentity> findByBirthMonthAndBirthYear(int birthMonth, int birthYear);
    }

    interface SourceConflictRepository {
        SourceConflictSnapshot save(SourceConflictSnapshot sourceConflictSnapshot);

        List<SourceConflictSnapshot> findActiveByUserId(long userId);
    }
}
