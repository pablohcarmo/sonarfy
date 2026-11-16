package br.com.pablohcarmo.sonarfy.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.http.HttpMethod;

import java.io.IOException;
import java.util.List;

public class AuthCsrfFilter extends OncePerRequestFilter {
    private static final RequestMatcher SESSION_REQUESTS = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/login"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/refresh"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/logout"));
    private final List<String> allowedOrigins;

    public AuthCsrfFilter(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }

    static boolean isSessionRequest(HttpServletRequest request) { return SESSION_REQUESTS.matches(request); }

    // O cabeçalho obrigatório impede formulários simples; origens externas dependem de preflight restrito.
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (isSessionRequest(request)) {
            response.setHeader("Cache-Control", "no-store");
            String origin = request.getHeader("Origin");
            if (!"1".equals(request.getHeader("X-Sonarfy-CSRF"))
                    || (origin != null && !allowedOrigins.contains(origin))) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
