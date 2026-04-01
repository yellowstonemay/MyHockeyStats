package com.myhockeystats.service.integration;

import com.myhockeystats.api.dto.integration.IntegrationDtos.TeamHistoryDto;
import com.myhockeystats.api.dto.integration.IntegrationDtos.UnifiedHistoryDto;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class UnifiedHistoryService {

        private static final List<String> ALL_SOURCES = List.of("THF", "AYHL", "AHF");

        private final IntegrationDataService integrationDataService;

        public UnifiedHistoryService(IntegrationDataService integrationDataService) {
                this.integrationDataService = integrationDataService;
        }

    public UnifiedHistoryDto getUserHistory(Long userId, String season) {
                var context = integrationDataService.getPlayerContext(userId, null, 0, 0);
                if (context.isEmpty()) {
                        return new UnifiedHistoryDto(
                                        season == null ? "" : season,
                                        List.of(),
                                        ALL_SOURCES,
                                        List.of(),
                                        List.of(),
                                        List.of());
                }

                List<IntegrationDataService.PlayerIdentityLink> identityLinks = integrationDataService.findPersistedIdentityLinks(userId);
                boolean hasIdentityLinks = !identityLinks.isEmpty();

                List<String> availableSeasons = hasIdentityLinks
                                ? integrationDataService.findCareerAvailableSeasonsByIdentityLinks(identityLinks)
                                : integrationDataService.findCareerAvailableSeasons(context.get());
                String selectedSeason = season == null || season.isBlank()
                                ? chooseDefaultSeason(identityLinks, hasIdentityLinks, availableSeasons)
                                : season;

                List<IntegrationDataService.CareerHistoryRecord> records = hasIdentityLinks
                                ? integrationDataService.findCareerHistoryByIdentityLinks(identityLinks, selectedSeason)
                                : integrationDataService.findCareerHistory(context.get(), selectedSeason);

                List<TeamHistoryDto> teamHistory = records.stream()
                                .map(record -> new TeamHistoryDto(
                                                record.source(),
                                                record.sourcePlayerId(),
                                                fallback(record.sourceClubName(), null, record.source()),
                                                fallback(record.sourceTeamName(), null, "Unknown Team"),
                                                record.jerseyNumber(),
                                                record.gamesPlayed(),
                                                record.goals(),
                                                record.assists(),
                                                record.points(),
                                                record.penalties(),
                                                record.pim()))
                                .distinct()
                                .toList();

                List<String> sources = records.stream().map(IntegrationDataService.CareerHistoryRecord::source).distinct().toList();
                List<String> missingSources = new ArrayList<>();
                for (String source : ALL_SOURCES) {
                        if (sources.stream().noneMatch(value -> value.equalsIgnoreCase(source))) {
                                missingSources.add(source);
                        }
                }

                return new UnifiedHistoryDto(
                                selectedSeason,
                                sources,
                                missingSources,
                                teamHistory,
                                List.of(),
                                availableSeasons);
        }

        private String fallback(String preferred, String secondary, String fallback) {
                if (preferred != null && !preferred.isBlank()) {
                        return preferred;
                }
                if (secondary != null && !secondary.isBlank()) {
                        return secondary;
                }
                return fallback;
    }

        private String chooseDefaultSeason(
                        List<IntegrationDataService.PlayerIdentityLink> identityLinks,
                        boolean hasIdentityLinks,
                        List<String> availableSeasons) {
                if (availableSeasons.isEmpty()) {
                        return "";
                }
                if (!hasIdentityLinks) {
                        return availableSeasons.get(0);
                }

                String bestSeason = availableSeasons.get(0);
                int bestSourceCount = -1;
                int bestRecordCount = -1;

                for (String candidateSeason : availableSeasons) {
                        List<IntegrationDataService.CareerHistoryRecord> candidateRecords = integrationDataService
                                        .findCareerHistoryByIdentityLinks(identityLinks, candidateSeason);
                        int sourceCount = (int) candidateRecords.stream()
                                        .map(IntegrationDataService.CareerHistoryRecord::source)
                                        .distinct()
                                        .count();
                        int recordCount = candidateRecords.size();

                        if (sourceCount > bestSourceCount || (sourceCount == bestSourceCount && recordCount > bestRecordCount)) {
                                bestSeason = candidateSeason;
                                bestSourceCount = sourceCount;
                                bestRecordCount = recordCount;
                        }
                }

                return bestSeason;
        }
}
