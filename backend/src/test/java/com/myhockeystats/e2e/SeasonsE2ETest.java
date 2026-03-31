package com.myhockeystats.e2e;

import com.myhockeystats.model.AccountLink;
import com.myhockeystats.model.Player;
import com.myhockeystats.repository.AccountLinkRepository;
import com.myhockeystats.repository.PlayerRepository;
import com.myhockeystats.repository.integration.AyhlPlayerCareerRepository;
import com.myhockeystats.repository.integration.ThfPlayerCareerRepository;
import com.myhockeystats.repository.integration.AhfPlayerCareerRepository;
import com.myhocheystats.model.integration.AyhlPlayerCareer;
import com.myhocheystats.model.integration.ThfPlayerCareer;
import com.myhocheystats.model.integration.AhfPlayerCareer;
import com.myhocheystats.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.testcontainers.Testcontainers;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DisplayName("E2E Test: Player Seasons Lookup with Multi-Source Data")
public class SeasonsE2ETest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
    .withDatabaseName("hockey_e2e_test")
    .withUsername("test")
    .withPassword("test");

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private PlayerRepository playerRepository;

  @Autowired
  private AccountLinkRepository accountLinkRepository;

  @Autowired
  private AyhlPlayerCareerRepository ayhlRepository;

  @Autowired
  private ThfPlayerCareerRepository thfRepository;

  @Autowired
  private AhfPlayerCareerRepository ahfRepository;

  @Autowired
  private JwtTokenProvider jwtTokenProvider;

  private Player testPlayer;
  private Player linkedParent;
  private String playerToken;
  private String parentToken;

  @BeforeEach
  public void setup() {
    // Clear all data
    accountLinkRepository.deleteAll();
    playerRepository.deleteAll();
    ayhlRepository.deleteAll();
    thfRepository.deleteAll();
    ahfRepository.deleteAll();

    // Create test player
    testPlayer = new Player();
    testPlayer.setEmail("hockey.player@example.com");
    testPlayer.setFullName("Test Hockey Player");
    testPlayer.setBirthMonthYear("08/2010");
    playerRepository.save(testPlayer);

    // Create linked parent
    linkedParent = new Player();
    linkedParent.setEmail("hockey.parent@example.com");
    linkedParent.setFullName("Test Hockey Parent");
    linkedParent.setBirthMonthYear("05/1975");
    playerRepository.save(linkedParent);

    // Create account link
    AccountLink link = new AccountLink();
    link.setParentUserId(linkedParent.getId());
    link.setChildPlayerId(testPlayer.getId());
    link.setStatus("ACTIVE");
    accountLinkRepository.save(link);

    // Generate tokens
    playerToken = jwtTokenProvider.generateToken(testPlayer.getId().toString(), testPlayer.getEmail());
    parentToken = jwtTokenProvider.generateToken(linkedParent.getId().toString(), linkedParent.getEmail());

    // Seed multi-source career data (exact normalized name match: "test hockey player")
    // AYHL: 2025 season
    AyhlPlayerCareer ayhlRecord = new AyhlPlayerCareer();
    ayhlRecord.setPlayerName("test hockey player");  // normalized lowercase
    ayhlRecord.setSeason(2025);
    ayhlRecord.setClub("Minnesota AYHL Club");
    ayhlRecord.setTeam("U18 Premier");
    ayhlRecord.setJerseyNumber(15);
    ayhlRecord.setGamesPlayed(25);
    ayhlRecord.setGoals(8);
    ayhlRecord.setAssists(12);
    ayhlRecord.setPoints(20);
    ayhlRecord.setPenalties(5);
    ayhlRecord.setPim(10);
    ayhlRepository.save(ayhlRecord);

    // THF: 2024 season
    ThfPlayerCareer thfRecord = new ThfPlayerCareer();
    thfRecord.setPlayerName("test hockey player");
    thfRecord.setSeason(2024);
    thfRecord.setClub("Minnesota Thunder");
    thfRecord.setTeam("U17 Premier");
    thfRecord.setJerseyNumber(17);
    thfRecord.setGamesPlayed(22);
    thfRecord.setGoals(6);
    thfRecord.setAssists(10);
    thfRecord.setPoints(16);
    thfRecord.setPenalties(3);
    thfRecord.setPim(6);
    thfRepository.save(thfRecord);

    // AHF: 2024 season (ambiguous - same season as THF)
    AhfPlayerCareer ahfRecord = new AhfPlayerCareer();
    ahfRecord.setPlayerName("test hockey player");
    ahfRecord.setSeason(2024);
    ahfRecord.setClub("Local AHF Club");
    ahfRecord.setTeam("U17 AA");
    ahfRecord.setJerseyNumber(19);
    ahfRecord.setGamesPlayed(20);
    ahfRecord.setGoals(7);
    ahfRecord.setAssists(11);
    ahfRecord.setPoints(18);
    ahfRecord.setPenalties(2);
    ahfRecord.setPim(4);
    ahfRepository.save(ahfRecord);
  }

  @Test
  @DisplayName("Player can view own career records from all sources")
  public void testPlayerViewsOwnCareerRecords() throws Exception {
    mockMvc.perform(get("/api/players/" + testPlayer.getId() + "/seasons")
      .header("Authorization", "Bearer " + playerToken))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.playerId").value(testPlayer.getId().toString()))
      .andExpect(jsonPath("$.playerName").value("Test Hockey Player"))
      .andExpect(jsonPath("$.records", hasSize(3)))
      .andExpect(jsonPath("$.records[*].source").value(containsInAnyOrder("AYHL", "THF", "AHF")))
      .andExpect(jsonPath("$.availableSources", hasItems("AYHL", "THF", "AHF")))
      .andExpect(jsonPath("$.hasAmbiguity").value(true))
      .andExpect(jsonPath("$.ambiguityNote").isNotEmpty());
  }

  @Test
  @DisplayName("Records are sorted by season descending")
  public void testRecordsSortedBySeasonDesc() throws Exception {
    mockMvc.perform(get("/api/players/" + testPlayer.getId() + "/seasons")
      .header("Authorization", "Bearer " + playerToken))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.records[0].season").value(2025))  // First should be 2025
      .andExpect(jsonPath("$.records[1].season").value(2024))
      .andExpect(jsonPath("$.records[2].season").value(2024));
  }

  @Test
  @DisplayName("Career records include all hockey statistics")
  public void testRecordsIncludeAllStats() throws Exception {
    mockMvc.perform(get("/api/players/" + testPlayer.getId() + "/seasons")
      .header("Authorization", "Bearer " + playerToken))
      .andExpect(status().isOk())
      // Check that records have all statistics fields
      .andExpect(jsonPath("$.records[0].club").isNotEmpty())
      .andExpect(jsonPath("$.records[0].team").isNotEmpty())
      .andExpect(jsonPath("$.records[0].jerseyNumber").isNotEmpty())
      .andExpect(jsonPath("$.records[0].gamesPlayed").isNumber())
      .andExpect(jsonPath("$.records[0].goals").isNumber())
      .andExpect(jsonPath("$.records[0].assists").isNumber())
      .andExpect(jsonPath("$.records[0].points").isNumber())
      .andExpect(jsonPath("$.records[0].penalties").isNumber())
      .andExpect(jsonPath("$.records[0].pim").isNumber());
  }

  @Test
  @DisplayName("Ambiguity is detected for same season in multiple sources")
  public void testAmbiguityDetection() throws Exception {
    mockMvc.perform(get("/api/players/" + testPlayer.getId() + "/seasons")
      .header("Authorization", "Bearer " + playerToken))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.hasAmbiguity").value(true))
      .andExpect(jsonPath("$.ambiguityNote").value(containsString("Multiple")));
  }

  @Test
  @DisplayName("Linked parent can view child career records")
  public void testParentViewsLinkedChildRecords() throws Exception {
    mockMvc.perform(get("/api/players/" + testPlayer.getId() + "/seasons")
      .header("Authorization", "Bearer " + parentToken))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.playerId").value(testPlayer.getId().toString()))
      .andExpect(jsonPath("$.records", hasSize(3)))
      .andExpect(jsonPath("$.records[*].source").value(containsInAnyOrder("AYHL", "THF", "AHF")));
  }

  @Test
  @DisplayName("Cache control header is present and correct")
  public void testCacheControlHeader() throws Exception {
    mockMvc.perform(get("/api/players/" + testPlayer.getId() + "/seasons")
      .header("Authorization", "Bearer " + playerToken))
      .andExpect(status().isOk())
      .andExpect(result -> {
        String cacheControl = result.getResponse().getHeader("Cache-Control");
        assert(cacheControl != null);
        assert(cacheControl.contains("private"));
        assert(cacheControl.contains("max-age=300"));
      });
  }

  @Test
  @DisplayName("Specific AYHL record contains correct statistics")
  public void testAyhlRecordStats() throws Exception {
    mockMvc.perform(get("/api/players/" + testPlayer.getId() + "/seasons")
      .header("Authorization", "Bearer " + playerToken))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.records[?(@.source=='AYHL')]").isArray())
      .andExpect(jsonPath("$.records[?(@.source=='AYHL')][0].gamesPlayed").value(25))
      .andExpect(jsonPath("$.records[?(@.source=='AYHL')][0].goals").value(8))
      .andExpect(jsonPath("$.records[?(@.source=='AYHL')][0].assists").value(12))
      .andExpect(jsonPath("$.records[?(@.source=='AYHL')][0].points").value(20));
  }

  @Test
  @DisplayName("Separate rows per source even for same season")
  public void testMultipleRowsForSameSeason() throws Exception {
    // Verify 2024 season has multiple records from different sources
    mockMvc.perform(get("/api/players/" + testPlayer.getId() + "/seasons")
      .header("Authorization", "Bearer " + playerToken))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.records[?(@.season==2024)]", hasSize(2)))
      .andExpect(jsonPath("$.records[?(@.season==2024)][*].source").value(containsInAnyOrder("THF", "AHF")));
  }

  @Test
  @DisplayName("Source attribution is clear for each record")
  public void testSourceAttribution() throws Exception {
    mockMvc.perform(get("/api/players/" + testPlayer.getId() + "/seasons")
      .header("Authorization", "Bearer " + playerToken))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.records[*].source", hasItems("AYHL", "THF", "AHF")))
      .andExpect(jsonPath("$.records[*].sourcePlayerId").isArray());
  }

  @Test
  @DisplayName("Unauthenticated request returns 401")
  public void testUnauthenticatedAccess() throws Exception {
    mockMvc.perform(get("/api/players/" + testPlayer.getId() + "/seasons"))
      .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("Non-existent player returns 404")
  public void testNonExistentPlayer() throws Exception {
    mockMvc.perform(get("/api/players/00000000-0000-0000-0000-000000000000/seasons")
      .header("Authorization", "Bearer " + playerToken))
      .andExpect(status().isNotFound());
  }
}
