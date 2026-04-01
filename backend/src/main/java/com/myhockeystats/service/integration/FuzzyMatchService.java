package com.myhockeystats.service.integration;

import com.myhockeystats.model.integration.ImportDomainEntities.MatchMethod;
import org.springframework.stereotype.Service;

@Service
public class FuzzyMatchService {

    private final IdentityNormalizationService identityNormalizationService;

    public FuzzyMatchService(IdentityNormalizationService identityNormalizationService) {
        this.identityNormalizationService = identityNormalizationService;
    }

    public MatchResult score(String accountName, int birthYear, int birthMonth, String candidateName, int candidateBirthYear, int candidateBirthMonth) {
        if (birthYear != candidateBirthYear || birthMonth != candidateBirthMonth) {
            return new MatchResult(0.0, MatchMethod.FUZZY_CONFIRMED, false, "birth_month_year_mismatch");
        }

        String normalizedAccount = identityNormalizationService.normalizeName(accountName);
        String normalizedCandidate = identityNormalizationService.normalizeName(candidateName);
        if (normalizedAccount.isEmpty() || normalizedCandidate.isEmpty()) {
            return new MatchResult(0.0, MatchMethod.FUZZY_CONFIRMED, false, "missing_normalized_name");
        }

        if (normalizedAccount.equals(normalizedCandidate)) {
            return new MatchResult(1.0, MatchMethod.EXACT_NORMALIZED, true, "exact_normalized_match");
        }

        int distance = levenshteinDistance(normalizedAccount, normalizedCandidate);
        int maxLength = Math.max(normalizedAccount.length(), normalizedCandidate.length());
        double score = 1.0 - ((double) distance / (double) maxLength);
        boolean accepted = score >= 0.82;

        return new MatchResult(score, MatchMethod.FUZZY_CONFIRMED, accepted, accepted ? "fuzzy_name_match" : "below_threshold");
    }

    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    public record MatchResult(double score, MatchMethod method, boolean accepted, String reason) {
    }
}
