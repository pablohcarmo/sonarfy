package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.config.CorsConfig;
import br.com.pablohcarmo.sonarfy.config.SecurityConfig;
import br.com.pablohcarmo.sonarfy.dto.EchoSyncResponseDto;
import br.com.pablohcarmo.sonarfy.entities.Permission;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.services.EchoSyncService;
import br.com.pablohcarmo.sonarfy.services.EchoSyncService.EchoSyncResult;
import br.com.pablohcarmo.sonarfy.services.EchoSyncService.RetryAfterException;
import br.com.pablohcarmo.sonarfy.services.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitWebConfig(EchoesControllerTest.Config.class)
@TestPropertySource(properties = {
        "app.frontend-url=http://localhost:3000",
        "api.security.token.secret=echoes-controller-test-secret"
})
class EchoesControllerTest {

    @Configuration
    @EnableWebMvc
    @Import({SecurityConfig.class, CorsConfig.class, JwtService.class, EchoesController.class})
    static class Config {
        @Bean UserRepository userRepository() { return mock(UserRepository.class); }
        @Bean EchoSyncService echoSyncService() { return mock(EchoSyncService.class); }
    }

    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired EchoSyncService syncService;
    @Autowired JwtService jwt;
    MockMvc mvc;

    @BeforeEach
    void prepare() {
        reset(users, syncService);
        var role = new Permission();
        role.setName("ROLE_USER");
        var user = new User();
        user.setId(42L);
        user.setActive(true);
        user.setVerified(true);
        user.setPermission(role);
        when(users.findWithPermissionById(42L)).thenReturn(Optional.of(user));
        when(users.findById(42L)).thenReturn(Optional.of(user));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void syncRequiresValidAccessBearer() throws Exception {
        mvc.perform(post("/api/echoes/sync")).andExpect(status().isUnauthorized());
        for (String token : new String[]{"invalid", jwt.generateRegistrationToken("42")}) {
            mvc.perform(post("/api/echoes/sync").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(syncService);
    }

    @Test
    void syncUsesAuthenticatedUserIgnoringSuppliedUserIdAndDisablesCache() throws Exception {
        var newest = OffsetDateTime.parse("2026-11-22T01:00:00Z");
        var synced = OffsetDateTime.parse("2026-11-22T01:05:00Z");
        when(syncService.sync(42L)).thenReturn(new EchoSyncResult(
                new EchoSyncResponseDto(10, 4, 5, 1, true, false, newest, synced)));

        mvc.perform(post("/api/echoes/sync")
                        .header("Authorization", "Bearer " + jwt.generateAccessToken("42"))
                        .param("userId", "99"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.fetched").value(10))
                .andExpect(jsonPath("$.imported").value(4))
                .andExpect(jsonPath("$.alreadyKnown").value(5))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.possibleGap").value(false));

        verify(syncService).sync(42L);
        verifyNoMoreInteractions(syncService);
    }

    @Test
    void syncPropagatesRetryAfterOnCooldownAndProviderRateLimit() throws Exception {
        when(syncService.sync(42L))
                .thenThrow(new RetryAfterException(HttpStatus.TOO_MANY_REQUESTS, "Cooldown", 45L))
                .thenThrow(new RetryAfterException(HttpStatus.SERVICE_UNAVAILABLE, "Rate limit", 900L));

        mvc.perform(post("/api/echoes/sync").header("Authorization", "Bearer " + jwt.generateAccessToken("42")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Retry-After", "45"));

        mvc.perform(post("/api/echoes/sync").header("Authorization", "Bearer " + jwt.generateAccessToken("42")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Retry-After", "900"));
    }

    @Test
    void syncPropagatesConflictWithoutExposingSecrets() throws Exception {
        when(syncService.sync(42L))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Spotify reauthorization required."));

        mvc.perform(post("/api/echoes/sync").header("Authorization", "Bearer " + jwt.generateAccessToken("42")))
                .andExpect(status().isConflict())
                .andExpect(content().string(""));
    }

    // O filtro rejeita contas inelegíveis antes de chamar a sincronização.
    @Test
    void rejectsInactiveAndUnverifiedAccountsAtAuthentication() throws Exception {
        var user = users.findWithPermissionById(42L).orElseThrow();
        user.setActive(false);
        mvc.perform(post("/api/echoes/sync").header("Authorization", "Bearer " + jwt.generateAccessToken("42")))
                .andExpect(status().isUnauthorized());
        user.setActive(true);
        user.setVerified(false);
        mvc.perform(post("/api/echoes/sync").header("Authorization", "Bearer " + jwt.generateAccessToken("42")))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(syncService);
    }
}
