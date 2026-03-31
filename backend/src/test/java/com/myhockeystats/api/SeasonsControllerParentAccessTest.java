package com.myhockeystats.api;

import com.myhockeystats.model.AccountLink;
import com.myhockeystats.model.Player;
import com.myhockeystats.repository.AccountLinkRepository;
import com.myhockeystats.repository.PlayerRepository;
import com.myhockeystats.repository.integration.AyhlPlayerCareerRepository;
import com.myhockeystats.repository.integration.ThfPlayerCareerRepository;
import com.myhockeystats.repository.integration.AhfPlayerCareerRepository;
import com.myhocheystats.model.integration.AyhlPlayerCareer;
import com.myhocheystats.model.integration.ThfPlayerCareer;
import com.myhocheystats.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
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
public class SeasonsControllerParentAccessTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
    .withDatabaseName("hockey_test")
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

  private Player player;
  private Player parentUser;
  private Player unlinkedParentUser;
  private String playerToken;
  private String parentToken;
  private String unlinkedParentToken;

  @BeforeEach
  public void setup() {
    // Clear data
    accountLinkRepository.deleteAll();
    playerRepository.deleteAll();
    ayhlRepository.deleteAll();
    thfRepository.deleteAll();
    ahfRepository.deleteAll();

    // Create test player
    player = new Player();
    player.setEmail("john@example.com");
    player.setFullName("John Doe");
    player.setBirthMonthYear("05/2011");
    playerRepository.save(player);

    // Create linked parent
    parentUser = new Player();
    parentUser.setEmail("parent@example.com");
    parentUser.setFullName("Parent User");
    parentUser.setBirthMonthYear("01/1980");
    playerRepository.save(parentUser);

    // Create account link
    AccountLink link = new AccountLink();
    link.setParentUserId(parentUser.getId());
    link.setChildPlayerId(player.getId());
    link.setStatus("ACTIVE");
    accountLinkRepository.save(link);

    // Create unlinked parent
    unlinkedParentUser = new Player();
    unlinkedParentUser.setEmail("unlinked@example.com");
    unlinkedParentUser.setFullName("Unlinked Parent");
    unlinkedParentUser.setBirthMonthYear("03/1982");
    playerRepository.save(unlinkedParentUser);

    // Generate JWT tokens
    playerToken = jwtTokenProvider.generateToken(player.getId().toString(), player.getEmail());
    parentToken = jwtTokenProvider.generateToken(parentUser.getId().toString(), parentUser.getEmail());
    unlinkedParentToken = jwtTokenProvider.generateToken(unlinkedParentUser.getId().toString(), unlinkedParentUser.getEmail());

    // Seed career data
    AyhlPlayerCareer ayhlRecord = new AyhlPlayerCareer();
    ayhlRecord.setPlayerName("john doe");
    ayhlRecord.setSeason(2025);
    ayhlRecord.setClub("North Stars");
    ayhlRecord.setTeam("U18 AA");
    ayhlRecord.setJerseyNumber(12);
    ayhlRecord.setGamesPlayed(20);
    ayhlRecord.setGoals(5);
    ayhlRecord.setAssists(8);
    ayhlRecord.setPoints(13);
    ayhlRecord.setPenalties(4);
    ayhlRecord.setPim(8);
    ayhlRepository.save(ayhlRecord);
  }

  @Test
  public void testPlayerCanViewOwnSeasons() throws Exception {
    mockMvc.perform(get("/api/players/" + player.getId() + "/seasons")
      .header("Authorization", "Bearer " + playerToken))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.playerId").value(player.getId().toString()))
      .andExpect(jsonPath("$.records", hasSize(greaterThan(0))));
  }

  @Test
  public void testLinkedParentCanViewChildSeasons() throws Exception {
    mockMvc.perform(get("/api/players/" + player.getId() + "/seasons")
      .header("Authorization", "Bearer " + parentToken))
      .andExpect(status().isOk())
      .andExpect(jsonPath("$.playerId").value(player.getId().toString()))
      .andExpect(jsonPath("$.records", hasSize(greaterThan(0))));
  }

  @Test
  public void testUnlinkedParentCannotViewChildSeasons() throws Exception {
    mockMvc.perform(get("/api/players/" + player.getId() + "/seasons")
      .header("Authorization", "Bearer " + unlinkedParentToken))
      .andExpect(status().isUnauthorized());
  }

  @Test
  public void testNonAuthenticatedUserCannotViewSeasons() throws Exception {
    mockMvc.perform(get("/api/players/" + player.getId() + "/seasons"))
      .andExpect(status().isUnauthorized());
  }

  @Test
  public void testParentCanOnlyViewLinkedChildData() throws Exception {
    // Create another player not linked to parent
    Player anotherPlayer = new Player();
    anotherPlayer.setEmail("jane@example.com");
    anotherPlayer.setFullName("Jane Doe");
    anotherPlayer.setBirthMonthYear("06/2012");
    playerRepository.save(anotherPlayer);

    // Add career data for another player
    AyhlPlayerCareer ayhlRecord2 = new AyhlPlayerCareer();
    ayhlRecord2.setPlayerName("jane doe");
    ayhlRecord2.setSeason(2025);
    ayhlRecord2.setClub("Thunder");
    ayhlRecord2.setTeam("U16 AA");
    ayhlRecord2.setJerseyNumber(18);
    ayhlRecord2.setGamesPlayed(15);
    ayhlRecord2.setGoals(2);
    ayhlRecord2.setAssists(4);
    ayhlRecord2.setPoints(6);
    ayhlRecord2.setPenalties(1);
    ayhlRecord2.setPim(2);
    ayhlRepository.save(ayhlRecord2);

    // Parent can view linked player
    mockMvc.perform(get("/api/players/" + player.getId() + "/seasons")
      .header("Authorization", "Bearer " + parentToken))
      .andExpect(status().isOk());

    // Parent cannot view unlinked player
    mockMvc.perform(get("/api/players/" + anotherPlayer.getId() + "/seasons")
      .header("Authorization", "Bearer " + parentToken))
      .andExpect(status().isUnauthorized());
  }

  @Test
  public void testCacheControlHeaderPresent() throws Exception {
    mockMvc.perform(get("/api/players/" + player.getId() + "/seasons")
      .header("Authorization", "Bearer " + playerToken))
      .andExpect(status().isOk())
      .andExpect(result -> {
        String cacheControl = result.getResponse().getHeader("Cache-Control");
        assert(cacheControl != null && cacheControl.contains("private"));
        assert(cacheControl.contains("max-age=300"));
      });
  }

  @Test
  public void testPlayerNotFoundReturns404() throws Exception {
    Long nonExistentPlayerId = 999999L;
    mockMvc.perform(get("/api/players/" + nonExistentPlayerId + "/seasons")
      .header("Authorization", "Bearer " + playerToken))
      .andExpect(status().isNotFound());
  }
}
