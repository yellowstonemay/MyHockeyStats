package com.myhockeystats.model.integration;

public final class ImportDomainEntities {
    private ImportDomainEntities() {
    }

    public enum IntegrationSource {
        THF,
        AYHL,
        AHF
    }

    public enum ImportTriggerType {
        SCHEDULED_DAILY,
        OPERATOR_MANUAL
    }

    public enum ImportRunStatus {
        RUNNING,
        COMPLETED,
        FAILED,
        PARTIAL
    }

    public enum LinkState {
        PENDING_SELECTION,
        CONFIRMED,
        REVERIFY_REQUIRED,
        UNLINKED
    }

    public enum MatchMethod {
        EXACT_NORMALIZED,
        FUZZY_CONFIRMED
    }

    public enum ConflictType {
        GAME_STAT,
        TEAM_ASSIGNMENT,
        SCORE,
        OTHER
    }

}
