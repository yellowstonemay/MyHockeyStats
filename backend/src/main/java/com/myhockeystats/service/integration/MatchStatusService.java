package com.myhockeystats.service.integration;

import com.myhockeystats.api.dto.integration.IntegrationDtos.MatchCandidateDto;
import com.myhockeystats.api.dto.integration.IntegrationDtos.MatchLinkDto;
import com.myhockeystats.api.dto.integration.IntegrationDtos.MatchStatusDto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class MatchStatusService {

    private final IntegrationDataService integrationDataService;

    public MatchStatusService(IntegrationDataService integrationDataService) {
        this.integrationDataService = integrationDataService;
    }

    public MatchStatusDto getStatusForUser(Long userId, String fullName, int birthYear, int birthMonth) {
        var context = integrationDataService.getPlayerContext(userId, fullName, birthYear, birthMonth);
        if (context.isEmpty()) {
            return new MatchStatusDto("NO_MATCH", "", List.of(), List.of());
        }

        var playerContext = context.get();
        String birthMonthYear = String.format("%04d-%02d", playerContext.birthYear(), playerContext.birthMonth());

        List<MatchLinkDto> persistedLinks = integrationDataService.findPersistedIdentityLinks(userId).stream()
                .map(link -> new MatchLinkDto(link.source(), link.sourcePlayerId(), link.linkState(), link.lastVerifiedAt()))
                .toList();
        if (!persistedLinks.isEmpty()) {
            return new MatchStatusDto("LINKED", birthMonthYear, List.of(), persistedLinks);
        }

        List<IntegrationDataService.CareerCandidateRecord> careerCandidates = integrationDataService.findCareerCandidates(playerContext);
        if (careerCandidates.isEmpty()) {
            return new MatchStatusDto("NO_MATCH", birthMonthYear, List.of(), List.of());
        }

        Map<String, Integer> sourceCandidateCounts = new HashMap<>();
        for (IntegrationDataService.CareerCandidateRecord candidate : careerCandidates) {
            sourceCandidateCounts.merge(candidate.source(), 1, Integer::sum);
        }

        boolean ambiguousBySource = sourceCandidateCounts.values().stream().anyMatch(count -> count > 1);
        if (!ambiguousBySource) {
            List<MatchLinkDto> inferredLinks = careerCandidates.stream()
                    .map(candidate -> new MatchLinkDto(candidate.source(), candidate.sourcePlayerId(), "CONFIRMED", null))
                    .toList();
            return new MatchStatusDto("LINKED", birthMonthYear, List.of(), inferredLinks);
        }

        List<MatchCandidateDto> candidates = careerCandidates.stream()
                .map(candidate -> new MatchCandidateDto(
                        candidate.sourcePlayerId(),
                        candidate.source(),
                        candidate.playerName(),
                        candidate.seasonLabel(),
                        "EXACT_NORMALIZED",
                        1.0,
                        List.of("Exact normalized name match in career data")))
                .toList();

        return new MatchStatusDto(
                "AMBIGUOUS_SELECTION_REQUIRED",
                birthMonthYear,
                candidates,
                List.of());
    }

    public int confirmUserLinks(Long userId, List<ConfirmedCandidate> selectedCandidates) {
    List<IntegrationDataService.ConfirmedIdentitySelection> selectedIds = selectedCandidates.stream()
        .map(candidate -> new IntegrationDataService.ConfirmedIdentitySelection(candidate.source(), candidate.candidateId()))
                .toList();
    return integrationDataService.replaceConfirmedIdentityLinks(userId, selectedIds);
    }

    public record ConfirmedCandidate(String source, String candidateId) {
    }
}
