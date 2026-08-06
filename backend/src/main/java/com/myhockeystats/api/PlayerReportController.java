package com.myhockeystats.api;

import com.myhockeystats.model.User;
import com.myhockeystats.repository.UserRepository;
import com.myhockeystats.security.JwtUtil;
import com.myhockeystats.service.AiInsightService;
import com.myhockeystats.service.PlayerReportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Unified player report (free for every user) + AI season report (feature 2,
 * DeepSeek-backed, weekly/daily cost-capped).
 *
 * Feature 1: GET  /api/players/me/report                -> unified career view
 * Feature 2: GET  /api/players/me/report/insights?season -> cached AI report
 *            POST /api/players/me/report/insights        -> generate (capped)
 */
@RestController
@RequestMapping("/api/players/me/report")
public class PlayerReportController {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;
    private final PlayerReportService playerReportService;
    private final AiInsightService aiInsightService;

    public PlayerReportController(JwtUtil jwtUtil, UserRepository userRepository,
                                  PlayerReportService playerReportService,
                                  AiInsightService aiInsightService) {
        this.jwtUtil = jwtUtil;
        this.userRepository = userRepository;
        this.playerReportService = playerReportService;
        this.aiInsightService = aiInsightService;
    }

    private Optional<User> resolveUser(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String email = jwtUtil.extractEmail(authHeader.replace("Bearer ", "").trim());
        if (email == null || email.isBlank()) return Optional.empty();
        return userRepository.findByEmail(email);
    }

    /** GET /api/players/me/report — unified report (free for everyone). */
    @GetMapping
    public ResponseEntity<?> report(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        long uid = user.get().getId();

        Map<String, Object> report = playerReportService.buildReport(uid);

        // Attach cached AI insights (if any) + AI status so the UI can render
        // the Season Insights section without an extra call.
        Integer cachedSeason = aiInsightService.cachedSeason(uid);
        if (cachedSeason != null) {
            List<Map<String, Object>> insights = aiInsightService.getCached(uid, cachedSeason);
            if (insights != null) {
                report.put("insights", insights);
                report.put("insightSeason", cachedSeason);
            }
        }
        Map<String, Object> ai = new LinkedHashMap<>();
        ai.put("configured", aiInsightService.isConfigured());
        ai.put("weeklyUsed", aiInsightService.weeklyUsed(uid));
        ai.put("weeklyLimit", aiInsightService.weeklyPerUser());
        ai.put("dailyCostUsd", aiInsightService.dailyCostUsd());
        ai.put("dailyCapUsd", aiInsightService.dailyCapUsd());
        report.put("ai", ai);

        return ResponseEntity.ok(report);
    }

    /** GET /api/players/me/report/insights?season=YYYY — cached AI report for a season. */
    @GetMapping("/insights")
    public ResponseEntity<?> cachedInsights(@RequestParam(required = false) Integer season,
                                            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        long uid = user.get().getId();
        Integer s = season != null ? season : aiInsightService.cachedSeason(uid);
        if (s == null) {
            return ResponseEntity.ok(Map.of("status", "NOT_GENERATED"));
        }
        List<Map<String, Object>> insights = aiInsightService.getCached(uid, s);
        return ResponseEntity.ok(Map.of("status", insights == null ? "NOT_GENERATED" : "READY",
                "season", s, "insights", insights == null ? List.of() : insights));
    }

    /** POST /api/players/me/report/insights {season} — generate (weekly + daily cap enforced). */
    @PostMapping("/insights")
    public ResponseEntity<?> generate(@RequestBody(required = false) Map<String, Object> body,
                                      @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Optional<User> user = resolveUser(authHeader);
        if (user.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "Authentication required"));
        }
        long uid = user.get().getId();

        Integer season = body == null || body.get("season") == null
            ? null : ((Number) body.get("season")).intValue();
        if (season == null) {
            Map<String, Object> report = playerReportService.buildReport(uid);
            Object latest = report.get("latestSeason");
            season = latest == null ? null : ((Number) latest).intValue();
        }
        if (season == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "No season data to report on"));
        }

        try {
            Map<String, Object> out = aiInsightService.generate(uid, season);
            return ResponseEntity.ok(out);
        } catch (AiInsightService.NotConfiguredException e) {
            return ResponseEntity.status(503).body(Map.of("error", "AI report is not configured yet.", "code", "AI_NOT_CONFIGURED"));
        } catch (AiInsightService.WeeklyLimitException e) {
            return ResponseEntity.status(429).body(Map.of(
                "error", "You've used your AI report for this week (limit " + e.limit + "/week). Check back next week.",
                "code", "WEEKLY_LIMIT", "weeklyLimit", e.limit));
        } catch (AiInsightService.DailyCapException e) {
            return ResponseEntity.status(429).body(Map.of(
                "error", "Today's AI budget is used up. Try again tomorrow.",
                "code", "DAILY_CAP", "dailyCapUsd", e.cap));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "AI report failed: " + e.getMessage(), "code", "AI_ERROR"));
        }
    }
}
