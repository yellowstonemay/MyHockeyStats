package com.myhockeystats.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhockeystats.model.User;
import com.myhockeystats.repository.UserRepository;
import com.myhockeystats.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Admin-only bridge to the local school assistant (MyRHScore/assistant).
 *
 * The assistant is a small Python service that runs on the host next to this
 * stack (or on the same private network) and is NEVER exposed publicly - it holds
 * the scraped grade history and the model API key. This controller is the only
 * way to reach it, and it is gated by the same is_admin flag as the rest of
 * /api/admin, so the page is invisible and unusable for normal accounts.
 *
 * Configure with app.school.assistant-url (env ASSISTANT_URL),
 * default http://host.docker.internal:8787 (= the Docker host, i.e. the Mac mini).
 */
@RestController
@RequestMapping("/api/admin/school")
public class SchoolAssistantController {

    @Value("${app.school.assistant-url:http://host.docker.internal:8787}")
    private String assistantUrl;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private Optional<User> resolveAdmin(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String email = jwtUtil.extractEmail(authHeader.substring("Bearer ".length()));
        return userRepository.findByEmail(email).filter(User::isAdmin);
    }

    private ResponseEntity<?> forward(String path, String jsonBody, String authHeader) {
        if (resolveAdmin(authHeader).isEmpty()) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(assistantUrl + path))
                    .timeout(Duration.ofSeconds(180));
            if (jsonBody == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody));
            }
            HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            return ResponseEntity.status(response.statusCode())
                    .header("Content-Type", "application/json")
                    .body(response.body());
        } catch (Exception error) {
            // Include the exception TYPE and the target address: several failure
            // modes (unresolved host, refused port) carry a null message, and a
            // bare "null" is impossible to act on. Admin-only output.
            String detail = error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : ": " + error.getMessage());
            return ResponseEntity.status(503)
                    .body(Map.of("error", "school assistant unreachable at " + assistantUrl
                            + " (" + detail + ")"));
        }
    }

    /** GET /api/admin/school/status - data freshness + today's model spend. */
    @GetMapping("/status")
    public ResponseEntity<?> status(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        return forward("/api/status", null, authHeader);
    }

    /** POST /api/admin/school/ask {"question": "..."} - one question, one answer. */
    @PostMapping("/ask")
    public ResponseEntity<?> ask(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, Object> body) {
        String question = body == null ? null : String.valueOf(body.get("question"));
        if (question == null || question.isBlank() || "null".equals(question)) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }
        try {
            String json = objectMapper.writeValueAsString(Map.of("question", question));
            return forward("/api/ask", json, authHeader);
        } catch (Exception error) {
            return ResponseEntity.status(500).body(Map.of("error", error.getMessage()));
        }
    }
}
