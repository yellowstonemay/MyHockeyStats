package com.myhockeystats.service.integration;

import com.myhockeystats.api.dto.integration.IntegrationDtos.MatchCandidateDto;
import com.myhockeystats.api.dto.integration.IntegrationDtos.MatchLinkDto;
import com.myhockeystats.api.dto.integration.IntegrationDtos.MatchStatusDto;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MatchStatusService {

    private final IntegrationDataService integrationDataService;
    private final FuzzyMatchService fuzzyMatchService;

    public MatchStatusService(IntegrationDataService integrationDataService, FuzzyMatchService fuzzyMatchService) {
        this.integrationDataService = integrationDataService;
        this.fuzzyMatchService = fuzzyMatchService;
    }

    public MatchStatusDto getStatusForUser(Long userId, String fullName, int birthYear, int birthMonth) {
        var context = integrationDataService.getPlayerContext(userId, fullName, birthYear, birthMonth);
        if (context.isEmpty()) {
            return new MatchStatusDto("NO_MATCH", "", List.of(), List.of());
        }

        var playerContext = context.get();
        String birthMonthYear = String.format("%04d-%02d", playerContext.birthYear(), playerContext.birthMonth());

        List<MatchLinkDto> persistedLinks = integrationDataService.findPersistedLinks(userId).stream()
                .map(link -> new MatchLinkDto(link.source(), link.importedPlayerRecordId(), link.linkState(), link.lastVerifiedAt()))
                .toList();
        if (!persistedLinks.isEmpty()) {
            return new MatchStatusDto("LINKED", birthMonthYear, List.of(), persistedLinks);
        }

        List<IntegrationDataService.ImportedPlayerRecord> matchedRecords = integrationDataService.findBestMatches(playerContext, null);
        if (!matchedRecords.isEmpty()) {
            List<MatchLinkDto> inferredLinks = matchedRecords.stream()
                    .map(match -> new MatchLinkDto(match.source(), match.id(), "CONFIRMED", null))
                    .toList();
            return new MatchStatusDto("LINKED", birthMonthYear, List.of(), inferredLinks);
        }

        List<MatchCandidateDto> candidates = integrationDataService.findBirthMonthYearMatches(
                        playerContext.birthYear(), playerContext.birthMonth()).stream()
                .map(candidate -> {
                    FuzzyMatchService.MatchResult score = fuzzyMatchService.score(
                            playerContext.displayName(),
                            playerContext.birthYear(),
                            playerContext.birthMonth(),
                            candidate.playerNameRaw(),
                            candidate.birthYear(),
                            candidate.birthMonth());
                    if (!score.accepted()) {
                        return null;
                    }
                    return new MatchCandidateDto(
                            candidate.id(),
                            candidate.source(),
                            candidate.playerNameRaw(),
                            candidate.seasonLabel(),
                            score.method().name(),
                            score.score(),
                            List.of(score.reason()));
                })
                .filter(candidate -> candidate != null)
                .toList();

        if (candidates.isEmpty()) {
            return new MatchStatusDto("NO_MATCH", birthMonthYear, List.of(), List.of());
        }

        return new MatchStatusDto(
                "AMBIGUOUS_SELECTION_REQUIRED",
                birthMonthYear,
                candidates,
                List.of());
    }

    public int confirmUserLinks(Long userId, List<ConfirmedCandidate> selectedCandidates) {
        List<UUID> selectedIds = selectedCandidates.stream()
                .map(ConfirmedCandidate::candidateId)
                .toList();
        return integrationDataService.replaceConfirmedLinks(userId, selectedIds);
    }

    public record ConfirmedCandidate(String source, UUID candidateId) {
    }
}
