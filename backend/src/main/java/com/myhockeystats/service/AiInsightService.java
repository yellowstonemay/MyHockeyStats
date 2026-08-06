package com.myhockeystats.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI season report (feature 2). Generates natural-language insights for a
 * player's season via the DeepSeek API, subject to:
 *   - a per-user weekly quota (default 1/week), and
 *   - a global daily cost cap (default $0.20).
 *
 * Results are cached in {@code ai_insights} so re-viewing the same report that
 * week costs nothing; only a fresh generation burns quota/cost.
 */
@Service
public class AiInsightService {

    private final JdbcTemplate jdbcTemplate;
    private final PlayerReportService playerReportService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${app.ai.enabled:true}")
    private boolean enabled;
    @Value("${app.ai.model:deepseek-v4-flash}")
    private String model;
    @Value("${app.ai.base-url:https://api.deepseek.com}")
    private String baseUrl;
    @Value("${app.ai.api-key:}")
    private String apiKey;
    @Value("${app.ai.daily-cap-usd:0.20}")
    private double dailyCapUsd;
    @Value("${app.ai.weekly-per-user:1}")
    private int weeklyPerUser;
    @Value("${app.ai.max-output-tokens:2000}")
    private int maxOutputTokens;
    @Value("${app.ai.price-input-per-m:0.14}")
    private double priceInputPerM;
    @Value("${app.ai.price-output-per-m:0.28}")
    private double priceOutputPerM;

    public AiInsightService(JdbcTemplate jdbcTemplate, PlayerReportService playerReportService, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.playerReportService = playerReportService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    // ── config / quota helpers ────────────────────────────────────────────
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public int weeklyPerUser() {
        return weeklyPerUser;
    }

    public double dailyCapUsd() {
        return dailyCapUsd;
    }

    public int weeklyUsed(long userId) {
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Integer n = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ai_insights WHERE user_id = ? AND created_at >= ?",
            Integer.class, userId, monday.atStartOfDay());
        return n == null ? 0 : n;
    }

    public double dailyCostUsd() {
        Double d = jdbcTemplate.queryForObject(
            "SELECT COALESCE(SUM(cost_usd), 0) FROM ai_usage_daily WHERE day = CURRENT_DATE", Double.class);
        return d == null ? 0 : d;
    }

    /** Cached insights for a user+season, or null if none. */
    public List<Map<String, Object>> getCached(long userId, Integer season) {
        List<String> rows = jdbcTemplate.query(
            "SELECT insights_json FROM ai_insights WHERE user_id = ? AND season_year = ? " +
            "ORDER BY created_at DESC LIMIT 1",
            (rs, rn) -> rs.getString("insights_json"), userId, season);
        if (rows.isEmpty()) return null;
        try {
            return parseInsights(rows.get(0));
        } catch (Exception e) {
            return null;
        }
    }

    /** Which season has cached insights (for the report to show them). */
    public Integer cachedSeason(long userId) {
        List<Integer> rows = jdbcTemplate.query(
            "SELECT season_year FROM ai_insights WHERE user_id = ? ORDER BY created_at DESC LIMIT 1",
            (rs, rn) -> rs.getInt("season_year"), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ── generation ────────────────────────────────────────────────────────
    public Map<String, Object> generate(long userId, int season) {
        if (!isConfigured()) {
            throw new NotConfiguredException();
        }
        if (weeklyUsed(userId) >= weeklyPerUser) {
            throw new WeeklyLimitException(weeklyPerUser);
        }
        if (dailyCostUsd() >= dailyCapUsd) {
            throw new DailyCapException(dailyCapUsd);
        }

        // Build the compact numeric payload (numbers only; no PII beyond first name).
        Map<String, Object> payload = buildPayload(userId, season);
        String promptJson = toJson(payload);

        String body = callDeepSeek(promptJson);
        JsonNode usage = null;
        try {
            JsonNode root = objectMapper.readTree(body);
            usage = root.path("usage");
        } catch (Exception ignore) {
            // usage absent — cost ledger will use zeros
        }

        List<Map<String, Object>> insights;
        try {
            JsonNode root = objectMapper.readTree(body);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            insights = parseInsights(content);
        } catch (Exception e) {
            throw new RuntimeException("Could not parse AI response: " + e.getMessage(), e);
        }
        if (insights.isEmpty()) {
            throw new RuntimeException("AI returned no insights.");
        }

        int inTok = usage == null ? 0 : usage.path("prompt_tokens").asInt(0);
        int outTok = usage == null ? 0 : usage.path("completion_tokens").asInt(0);
        double cost = inTok * priceInputPerM / 1_000_000.0 + outTok * priceOutputPerM / 1_000_000.0;
        cost = Math.round(cost * 1_000_000.0) / 1_000_000.0;

        jdbcTemplate.update(
            "INSERT INTO ai_insights (id, user_id, season_year, source_json, insights_json, model, input_tokens, output_tokens, cost_usd, created_at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
            userId, season, promptJson, toJson(insights), model, inTok, outTok, cost);

        jdbcTemplate.update(
            "INSERT INTO ai_usage_daily (day, calls, input_tokens, output_tokens, cost_usd) VALUES (CURRENT_DATE, 1, ?, ?, ?) " +
            "ON CONFLICT (day) DO UPDATE SET calls = ai_usage_daily.calls + 1, " +
            "input_tokens = ai_usage_daily.input_tokens + EXCLUDED.input_tokens, " +
            "output_tokens = ai_usage_daily.output_tokens + EXCLUDED.output_tokens, " +
            "cost_usd = ai_usage_daily.cost_usd + EXCLUDED.cost_usd",
            inTok, outTok, cost);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("insights", insights);
        out.put("season", season);
        out.put("model", model);
        out.put("costUsd", cost);
        out.put("weeklyUsed", weeklyUsed(userId));
        out.put("weeklyLimit", weeklyPerUser);
        out.put("dailyCostUsd", dailyCostUsd());
        out.put("dailyCapUsd", dailyCapUsd);
        return out;
    }

    // ── payload / prompt ──────────────────────────────────────────────────
    private Map<String, Object> buildPayload(long userId, int season) {
        Map<String, Object> p = new LinkedHashMap<>();
        Map<String, Object> report = playerReportService.buildReport(userId);
        Object profileObj = report.get("profile");
        Map<String, Object> profile = profileObj instanceof Map ? (Map<String, Object>) profileObj : Map.of();
        Map<String, Object> player = new LinkedHashMap<>();
        String fullName = (String) profile.getOrDefault("fullName", "");
        player.put("name", fullName == null || fullName.isBlank() ? "player" : fullName.split("\\s+")[0]);
        player.put("age", profile.get("age"));
        player.put("position", profile.get("position"));
        p.put("player", player);

        Map<String, Object> summary = playerReportService.seasonSummary(userId, season);
        p.put("season", summary.get("season"));
        p.put("previousSeason", summary.get("previousSeason"));
        p.put("rankings", summary.get("rankings"));

        // Career totals.
        int g = 0, a = 0, pim = 0, gp = 0;
        for (PlayerReportService.CareerRow r : playerReportService.careerRows(userId)) {
            gp += nz(r.games()); g += nz(r.goals()); a += nz(r.assists()); pim += nz(r.pim());
        }
        p.put("careerTotals", Map.of("games", gp, "goals", g, "assists", a, "points", g + a, "pim", pim));
        return p;
    }

    private String callDeepSeek(String promptJson) {
        String system = "You are a youth hockey development analyst. Given a compact JSON of one " +
            "player's season stats, write a specific, honest scouting-style season report. " +
            "Return ONLY valid JSON matching {\"insights\":[{\"title\": string, \"tone\": " +
            "\"positive\"|\"watch\"|\"neutral\", \"body\": string}]} with 3 to 5 insights " +
            "(e.g. scoring trend, playmaking vs finishing, discipline/watch item, how they rank, " +
            "what's next). No markdown, no lists, no PII beyond first name, plain prose bodies.";
        String user = "Here is the player's data as JSON: " + promptJson +
            "\n\nWrite the season report and return the JSON described in the system prompt.";

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", model);
        req.put("messages", List.of(
            Map.of("role", "system", "content", system),
            Map.of("role", "user", "content", user)));
        req.put("response_format", Map.of("type", "json_object"));
        req.put("max_tokens", maxOutputTokens);

        try {
            String reqBody = objectMapper.writeValueAsString(req);
            HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(90))
                .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                .build();
            HttpResponse<String> resp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("DeepSeek HTTP " + resp.statusCode() + ": " +
                    resp.body().substring(0, Math.min(300, resp.body().length())));
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("AI request interrupted", e);
        } catch (Exception e) {
            throw new RuntimeException("AI request failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseInsights(String content) throws Exception {
        String trimmed = content == null ? "" : content.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            trimmed = trimmed.substring(start, end + 1);
        }
        JsonNode node = objectMapper.readTree(trimmed);
        JsonNode arr = node.isArray() ? node : node.path("insights");
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (!n.isObject()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", n.path("title").asText(""));
            m.put("tone", n.path("tone").asText("neutral"));
            m.put("body", n.path("body").asText(""));
            if (!((String) m.get("body")).isBlank()) out.add(m);
        }
        return out;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialize failed", e);
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    // ── exceptions (mapped to HTTP codes by the controller) ───────────────
    public static class NotConfiguredException extends RuntimeException {}
    public static class WeeklyLimitException extends RuntimeException {
        public final int limit;
        public WeeklyLimitException(int limit) { this.limit = limit; }
    }
    public static class DailyCapException extends RuntimeException {
        public final double cap;
        public DailyCapException(double cap) { this.cap = cap; }
    }
}
