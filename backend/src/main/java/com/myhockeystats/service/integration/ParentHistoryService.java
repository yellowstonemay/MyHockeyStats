package com.myhockeystats.service.integration;

import com.myhockeystats.api.dto.integration.IntegrationDtos.UnifiedHistoryDto;
import org.springframework.stereotype.Service;

@Service
public class ParentHistoryService {

    private final UnifiedHistoryService unifiedHistoryService;

    public ParentHistoryService(UnifiedHistoryService unifiedHistoryService) {
        this.unifiedHistoryService = unifiedHistoryService;
    }

    public UnifiedHistoryDto getChildHistory(Long playerUserId, String season) {
        return unifiedHistoryService.getUserHistory(playerUserId, season);
    }
}
