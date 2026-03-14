package com.myhockeystats.service.integration;

import com.myhockeystats.api.dto.integration.IntegrationDtos.GameConflictDto;
import com.myhockeystats.api.dto.integration.IntegrationDtos.GameSourceValueDto;
import com.myhockeystats.api.dto.integration.IntegrationDtos.TeamHistoryDto;
import com.myhockeystats.api.dto.integration.IntegrationDtos.UnifiedGameDto;
import com.myhockeystats.api.dto.integration.IntegrationDtos.UnifiedHistoryDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class UnifiedHistoryService {

        private static final List<String> ALL_SOURCES = List.of("THF", "AYHL", "GAMESHEET");

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

                List<String> availableSeasons = integrationDataService.findAvailableSeasons(context.get());
                String selectedSeason = season == null || season.isBlank()
                                ? (availableSeasons.isEmpty() ? "" : availableSeasons.get(0))
                                : season;

                List<IntegrationDataService.ImportedPlayerRecord> records = integrationDataService.findBestMatches(context.get(), selectedSeason);

                List<TeamHistoryDto> teamHistory = records.stream()
                                .map(record -> new TeamHistoryDto(
                                                record.source(),
                                                fallback(record.sourceClubName(), record.sourceClubId(), record.source()),
                                                fallback(record.sourceTeamName(), record.sourceTeamId(), "Unknown Team")))
                                .distinct()
                                .toList();

                List<String> sources = records.stream().map(IntegrationDataService.ImportedPlayerRecord::source).distinct().toList();
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
                                List.<UnifiedGameDto>of(),
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
}
