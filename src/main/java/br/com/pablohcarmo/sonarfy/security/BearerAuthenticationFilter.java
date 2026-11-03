package br.com.pablohcarmo.sonarfy.security;

import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.services.JwtService;
import br.com.pablohcarmo.sonarfy.entities.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

// Usa a estrutura do Spring para processar a requisição uma vez nessa passagem pelo filtro.
public class BearerAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public BearerAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Esta rota valida um Bearer de cadastro, não um token de acesso.
        return "/api/auth/update-pending-email".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, // Contém os dados recebidos.
            HttpServletResponse response, // Permite devolver uma resposta, tal como 401.
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            unauthorized(response);
            return;
        }

        String userId = jwtService.validateAccessTokenAndGetUserId(header.substring(7).trim());
        if (userId == null || userId.isBlank()) {
            unauthorized(response);
            return;
        }

        long id;
        try {
            id = Long.parseLong(userId);
        } catch (NumberFormatException e) {
            unauthorized(response);
            return;
        }

        User user = userRepository.findWithPermissionById(id).orElse(null);
        if (user == null || !user.isEnabled()) {
            unauthorized(response);
            return;
        }

        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                user, null, user.getAuthorities());
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        // Encaminha a requisição para a próxima etapa
        filterChain.doFilter(request, response);
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
