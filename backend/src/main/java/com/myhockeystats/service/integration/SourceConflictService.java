package com.myhockeystats.service.integration;

import com.myhockeystats.model.integration.ImportDomainEntities.ConflictType;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class SourceConflictService {

    public ConflictResult evaluateFieldConflict(String fieldName, Map<String, String> sourceValues) {
        if (sourceValues == null || sourceValues.size() < 2) {
            return new ConflictResult(false, ConflictType.OTHER, fieldName, Map.of());
        }

        String baseline = null;
        boolean conflict = false;
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sourceValues.entrySet()) {
            String value = normalizeValue(entry.getValue());
            normalized.put(entry.getKey(), value);
            if (baseline == null) {
                baseline = value;
            } else if (!Objects.equals(baseline, value)) {
                conflict = true;
            }
        }

        return new ConflictResult(conflict, inferConflictType(fieldName), fieldName, normalized);
    }

    public Map<String, ConflictResult> evaluateRecordConflicts(Map<String, Map<String, String>> fieldToSourceValues) {
        Map<String, ConflictResult> results = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : fieldToSourceValues.entrySet()) {
            results.put(entry.getKey(), evaluateFieldConflict(entry.getKey(), entry.getValue()));
        }
        return results;
    }

    private ConflictType inferConflictType(String fieldName) {
        if (fieldName == null) {
            return ConflictType.OTHER;
        }
        String normalized = fieldName.toLowerCase();
        if (normalized.contains("goal") || normalized.contains("assist") || normalized.contains("point")) {
            return ConflictType.GAME_STAT;
        }
        if (normalized.contains("score")) {
            return ConflictType.SCORE;
        }
        if (normalized.contains("team") || normalized.contains("club")) {
            return ConflictType.TEAM_ASSIGNMENT;
        }
        return ConflictType.OTHER;
    }

    private String normalizeValue(String value) {
        if (value == null) {
            return "<null>";
        }
        return value.trim();
    }

    public record ConflictResult(boolean hasConflict, ConflictType conflictType, String fieldName, Map<String, String> sourceValues) {
    }
}
