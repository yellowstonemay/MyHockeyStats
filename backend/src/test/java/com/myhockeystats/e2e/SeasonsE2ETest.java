package com.myhockeystats.e2e;

import com.myhockeystats.api.SeasonsController;
import com.myhockeystats.api.dto.integration.IntegrationDtos;
import com.myhockeystats.model.PlayerProfile;
import com.myhockeystats.security.IntegrationAccessGuard;
import com.myhockeystats.service.PlayerProfileService;
import com.myhockeystats.service.integration.CareerLookupService;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SeasonsE2ETest {

  private MockMvc mockMvc;
  private IntegrationAccessGuard accessGuard;
  private PlayerProfileService playerProfileService;
  private CareerLookupService careerLookupService;

  @BeforeEach
  void setUp() {
    SeasonsController controller = new SeasonsController();
    accessGuard = Mockito.mock(IntegrationAccessGuard.class);
    playerProfileService = Mockito.mock(PlayerProfileService.class);
    careerLookupService = Mockito.mock(CareerLookupService.class);

    ReflectionTestUtils.setField(controller, "accessGuard", accessGuard);
    ReflectionTestUtils.setField(controller, "playerProfileService", playerProfileService);
    ReflectionTestUtils.setField(controller, "careerLookupService", careerLookupService);

    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    SecurityContextHolder.clearContext();
  }

  @Test
  void returns404WhenPlayerProfileNotFound() throws Exception {
    UUID playerId = UUID.randomUUID();
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken("viewer", "n/a", Collections.emptyList()));

    Mockito.when(accessGuard.canViewSeasons(eq("viewer"), eq(playerId.toString()))).thenReturn(true);
    Mockito.when(playerProfileService.getPlayerProfile(eq(playerId.toString()))).thenReturn(Optional.empty());

    mockMvc.perform(get("/api/players/{playerId}/seasons", playerId))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns200AndCacheHeaderForSuccessfulLookup() throws Exception {
    UUID playerId = UUID.randomUUID();
    PlayerProfile profile = new PlayerProfile();
    profile.setFullName("John Doe");

    IntegrationDtos.SeasonsResponseDto serviceResponse = new IntegrationDtos.SeasonsResponseDto(
        null,
        null,
        Collections.emptyList(),
        false,
        null,
        Set.of(),
        Set.of("AYHL", "THF", "AHF"),
        OffsetDateTime.now(),
        "private, max-age=300"
    );

    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken("viewer", "n/a", Collections.emptyList()));
    Mockito.when(accessGuard.canViewSeasons(eq("viewer"), eq(playerId.toString()))).thenReturn(true);
    Mockito.when(playerProfileService.getPlayerProfile(eq(playerId.toString()))).thenReturn(Optional.of(profile));
    Mockito.when(careerLookupService.lookupCareerRecordsByName(anyString())).thenReturn(serviceResponse);

    mockMvc.perform(get("/api/players/{playerId}/seasons", playerId))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=300")));
  }
}
