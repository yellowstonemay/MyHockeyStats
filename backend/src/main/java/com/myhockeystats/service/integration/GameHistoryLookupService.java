package com.myhockeystats.service.integration;

import com.myhockeystats.api.dto.integration.IntegrationDtos;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class GameHistoryLookupService {

    private static final Map<String, String> SOURCE_TABLES = Map.of(
        "AYHL", "ayhl_player_games",
        "THF", "thf_player_games",
        "AHF", "ahf_player_games",
        "NJHS", "njhs_player_games"
    );

    private final JdbcTemplate jdbcTemplate;

    public GameHistoryLookupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public IntegrationDtos.GameHistoryResponseDto lookupByPlayerName(
        String playerId,
        String playerName,
        Integer requestedSeasonYear
    ) {
        Set<String> canonicalNames = buildCanonicalLookupKeys(playerName);
        List<IntegrationDtos.GameHistoryGameDto> allGames = new ArrayList<>();

        for (Map.Entry<String, String> entry : SOURCE_TABLES.entrySet()) {
            String source = entry.getKey();
            String tableName = entry.getValue();
            if (!tableExists(tableName) || canonicalNames.isEmpty()) {
                continue;
            }
            allGames.addAll(loadGamesForSource(source, tableName, canonicalNames));
        }

        List<IntegrationDtos.GameHistorySeasonOptionDto> seasonOptions = allGames.stream()
            .filter(g -> g.seasonYear() != null)
            .map(g -> new IntegrationDtos.GameHistorySeasonOptionDto(g.seasonYear(), g.seasonLabel()))
            .collect(Collectors.toMap(
                IntegrationDtos.GameHistorySeasonOptionDto::seasonYear,
                x -> x,
                (a, b) -> a
            ))
            .values()
            .stream()
            .sorted(Comparator.comparing(IntegrationDtos.GameHistorySeasonOptionDto::seasonYear).reversed())
            .toList();

        Integer selectedSeasonYear = requestedSeasonYear;
        if (selectedSeasonYear == null && !seasonOptions.isEmpty()) {
            selectedSeasonYear = seasonOptions.get(0).seasonYear();
        }

        Integer finalSelectedSeasonYear = selectedSeasonYear;
        List<IntegrationDtos.GameHistoryGameDto> filteredGames = allGames.stream()
            .filter(g -> finalSelectedSeasonYear == null || finalSelectedSeasonYear.equals(g.seasonYear()))
            .sorted(Comparator
                .comparing(IntegrationDtos.GameHistoryGameDto::gameDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(IntegrationDtos.GameHistoryGameDto::source)
                .thenComparing(IntegrationDtos.GameHistoryGameDto::gameId, Comparator.nullsLast(String::compareTo))
            )
            .toList();

        String selectedSeasonLabel = seasonOptions.stream()
            .filter(s -> finalSelectedSeasonYear != null && finalSelectedSeasonYear.equals(s.seasonYear()))
            .map(IntegrationDtos.GameHistorySeasonOptionDto::seasonLabel)
            .findFirst()
            .orElse(null);

        return new IntegrationDtos.GameHistoryResponseDto(
            playerId,
            playerName,
            finalSelectedSeasonYear,
            selectedSeasonLabel,
            seasonOptions,
            filteredGames,
            OffsetDateTime.now(ZoneOffset.UTC),
            "private, max-age=300"
        );
    }

    private List<IntegrationDtos.GameHistoryGameDto> loadGamesForSource(
        String source,
        String tableName,
        Collection<String> canonicalNames
    ) {
        String placeholders = canonicalNames.stream().map(x -> "?").collect(Collectors.joining(","));
        String sql = "SELECT season_year, season_label, game_id, game_date, game_type, league, team_for, team_against, goals, assists, points, pim "
            + "FROM " + tableName + " "
            + "WHERE LOWER(REGEXP_REPLACE(COALESCE(player_name, ''), '[[:space:],.''-]', '', 'g')) IN (" + placeholders + ")";

        List<String> params = canonicalNames.stream().toList();
        return jdbcTemplate.query(sql, ps -> {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
        }, (rs, rowNum) -> {
            Date date = rs.getDate("game_date");
            return new IntegrationDtos.GameHistoryGameDto(
                source,
                (Integer) rs.getObject("season_year"),
                rs.getString("season_label"),
                rs.getString("game_id"),
                date != null ? date.toLocalDate() : null,
                rs.getString("game_type"),
                rs.getString("league"),
                rs.getString("team_for"),
                rs.getString("team_against"),
                (Integer) rs.getObject("goals"),
                (Integer) rs.getObject("assists"),
                (Integer) rs.getObject("points"),
                (Integer) rs.getObject("pim")
            );
        });
    }

    private boolean tableExists(String tableName) {
        Integer exists = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = current_schema() AND table_name = ?",
            Integer.class,
            tableName
        );
        return exists != null && exists > 0;
    }

    private static String normalizePlayerName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "";
        }

        return fullName
            .toLowerCase()
            .trim()
            .replaceAll("['-.]", "")
            .replaceAll("\\s+", " ");
    }

    private static String canonicalName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
            .toLowerCase()
            .trim()
            .replaceAll("[\\s,.'-]", "");
    }

    private static Set<String> buildCanonicalLookupKeys(String playerName) {
        String normalized = normalizePlayerName(playerName);
        if (normalized.isBlank()) {
            return Set.of();
        }

        Set<String> keys = new LinkedHashSet<>();
        keys.add(canonicalName(normalized));

        String[] parts = normalized.split(" ");
        if (parts.length == 2) {
            keys.add(canonicalName(parts[1] + "," + parts[0]));
        }

        if (normalized.contains(",")) {
            String[] csv = normalized.split(",", 2);
            if (csv.length == 2) {
                keys.add(canonicalName(csv[1] + " " + csv[0]));
            }
        }

        return keys.stream().filter(s -> !s.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
