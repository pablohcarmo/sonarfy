package br.com.pablohcarmo.sonarfy.security;

import br.com.pablohcarmo.sonarfy.entities.Permission;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.services.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.servlet.FilterChain;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BearerAuthenticationFilterTest {
    private final JwtService jwt = mock(JwtService.class);
    private final UserRepository users = mock(UserRepository.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final BearerAuthenticationFilter filter = new BearerAuthenticationFilter(jwt, users);
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        request.setServletPath("/api/users/me");
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void missingHeaderContinuesWithoutAuthenticating() throws Exception {
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(jwt, users);
    }

    @Test
    void pendingEmailKeepsRegistrationBearerForController() throws Exception {
        request.setServletPath("/api/auth/update-pending-email");
        request.addHeader("Authorization", "Bearer registration-token");
        filter.doFilter(request, response, chain);
        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwt, users);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Basic credentials", "Bearer", "Bearer invalid"})
    void malformedOrInvalidCredentialStopsWithUnauthorized(String header) throws Exception {
        request.addHeader("Authorization", header);
        filter.doFilter(request, response, chain);
        assertUnauthorized();
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "abc", "9223372036854775808"})
    void invalidSubjectStopsBeforeRepositoryLookup(String subject) throws Exception {
        request.addHeader("Authorization", "Bearer token");
        when(jwt.validateAccessTokenAndGetUserId("token")).thenReturn(subject);
        filter.doFilter(request, response, chain);
        assertUnauthorized();
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "inactive", "unverified", "enabled"})
    void onlyExistingEnabledAccountEstablishesPrincipal(String scenario) throws Exception {
        request.addHeader("Authorization", "bEaReR token");
        when(jwt.validateAccessTokenAndGetUserId("token")).thenReturn("42");
        User user = new User();
        user.setEmail("user@example.com");
        user.setActive(!scenario.equals("inactive"));
        user.setVerified(!scenario.equals("unverified"));
        user.setPermission(new Permission(1L, "ROLE_USER"));
        when(users.findWithPermissionById(42L))
                .thenReturn(scenario.equals("missing") ? Optional.empty() : Optional.of(user));
        filter.doFilter(request, response, chain);
        if (!scenario.equals("enabled")) {
            assertUnauthorized();
            return;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertSame(user, authentication.getPrincipal());
        assertTrue(authentication.isAuthenticated());
        assertEquals("user@example.com", authentication.getName());
        assertEquals("ROLE_USER", authentication.getAuthorities().iterator().next().getAuthority());
        verify(chain).doFilter(request, response);
    }

    private void assertUnauthorized() throws Exception {
        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(chain);
    }
}
