package com.myhockeystats.service.integration;

import com.myhockeystats.api.dto.integration.IntegrationDtos.ConfirmCandidateDto;
import com.myhockeystats.api.dto.integration.IntegrationDtos.ConfirmMatchResponse;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class MatchConfirmationService {

    private final MatchStatusService matchStatusService;

    public MatchConfirmationService(MatchStatusService matchStatusService) {
        this.matchStatusService = matchStatusService;
    }

    public ConfirmMatchResponse confirm(Long userId, List<ConfirmCandidateDto> candidates) {
        List<MatchStatusService.ConfirmedCandidate> selections = candidates.stream()
                .map(c -> new MatchStatusService.ConfirmedCandidate(c.source(), c.candidateId()))
                .collect(Collectors.toList());

        int updatedLinks = matchStatusService.confirmUserLinks(userId, selections);
        return new ConfirmMatchResponse("CONFIRMED", updatedLinks);
    }
}
