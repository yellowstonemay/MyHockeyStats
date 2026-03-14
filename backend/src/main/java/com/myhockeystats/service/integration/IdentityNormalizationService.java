package com.myhockeystats.service.integration;

import java.text.Normalizer;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

@Service
public class IdentityNormalizationService {

    public String normalizeName(String name) {
        if (name == null) {
            return "";
        }

        String normalized = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized;
    }

    public String birthMonthYearKey(int birthYear, int birthMonth) {
        if (birthMonth < 1 || birthMonth > 12) {
            throw new IllegalArgumentException("birthMonth must be between 1 and 12");
        }
        return String.format("%04d-%02d", birthYear, birthMonth);
    }

    public String birthMonthYearKey(LocalDate birthDate) {
        return birthMonthYearKey(birthDate.getYear(), birthDate.getMonthValue());
    }
}
