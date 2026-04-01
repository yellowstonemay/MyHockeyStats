package com.myhockeystats.api;

import com.myhockeystats.security.IntegrationAccessGuard;
import com.myhockeystats.service.PlayerProfileService;
import com.myhockeystats.service.integration.CareerLookupService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SeasonsControllerParentAccessTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    SeasonsController controller = new SeasonsController();
    ReflectionTestUtils.setField(controller, "playerProfileService", Mockito.mock(PlayerProfileService.class));
    ReflectionTestUtils.setField(controller, "careerLookupService", Mockito.mock(CareerLookupService.class));
    ReflectionTestUtils.setField(controller, "accessGuard", Mockito.mock(IntegrationAccessGuard.class));
    mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    SecurityContextHolder.clearContext();
  }

  @Test
  void unauthenticatedRequestReturns401() throws Exception {
    mockMvc.perform(get("/api/players/{playerId}/seasons", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }
}
