package com.myhockeystats.service.integration;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class IntegrationAuditLogService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IntegrationAuditLogService.class);

    public void logEvent(String correlationId, String eventType, Map<String, Object> attributes) {
        LOGGER.info("integration_event correlationId={} eventType={} attributes={}", correlationId, eventType, attributes);
    }

    public void logError(String correlationId, String eventType, String message, Throwable throwable) {
        LOGGER.error(
                "integration_error correlationId={} eventType={} message={}",
                correlationId,
                eventType,
                message,
                throwable);
    }
}
