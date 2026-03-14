package com.myhockeystats.service.integration;

import com.myhockeystats.model.integration.ImportDomainEntities.IntegrationSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class SourceParserService {

    public List<ParsedPlayerRow> parseCsv(IntegrationSource source, Path csvPath) throws IOException {
        if (!Files.exists(csvPath)) {
            return List.of();
        }

        List<String> lines = Files.readAllLines(csvPath);
        if (lines.size() <= 1) {
            return List.of();
        }

        String[] headers = lines.get(0).split(",");
        int nameIndex = indexOf(headers, "name", "player", "player_name");
        int birthYearIndex = indexOf(headers, "birth_year", "birthyear", "year");
        int birthMonthIndex = indexOf(headers, "birth_month", "birthmonth", "month");
        int seasonIndex = indexOf(headers, "season", "season_label");

        List<ParsedPlayerRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] values = lines.get(i).split(",");
            String displayName = safe(values, nameIndex);
            if (displayName.isBlank()) {
                continue;
            }
            int birthYear = parseInt(safe(values, birthYearIndex));
            int birthMonth = parseInt(safe(values, birthMonthIndex));
            String season = safe(values, seasonIndex);
            rows.add(new ParsedPlayerRow(source, displayName, birthYear, birthMonth, season));
        }
        return rows;
    }

    private int indexOf(String[] headers, String... names) {
        for (int i = 0; i < headers.length; i++) {
            String normalized = headers[i].trim().toLowerCase(Locale.ROOT);
            for (String name : names) {
                if (normalized.equals(name)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private String safe(String[] values, int index) {
        if (index < 0 || index >= values.length) {
            return "";
        }
        return values[index].trim();
    }

    private int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    public record ParsedPlayerRow(IntegrationSource source, String displayName, int birthYear, int birthMonth, String seasonLabel) {
    }
}
