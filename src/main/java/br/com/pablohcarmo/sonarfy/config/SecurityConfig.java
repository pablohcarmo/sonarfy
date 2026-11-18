package br.com.pablohcarmo.sonarfy.config;

import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.security.BearerAuthenticationFilter;
import br.com.pablohcarmo.sonarfy.security.AuthCsrfFilter;
import br.com.pablohcarmo.sonarfy.services.JwtService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@Import(CorsConfig.class)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService,
                                                   UserRepository userRepository, CorsConfig cors) throws Exception {
        return http
                // Integra o CORS à segurança para processar o preflight antes da autenticação.
                .cors(Customizer.withDefaults())

                // Bearer não usa cookie; login, refresh e logout recebem proteção pelo AuthCsrfFilter.
                .csrf(AbstractHttpConfigurer::disable)

                // Transforma a API em Stateless (náo guarda a sessão do usuário)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .exceptionHandling(exceptions -> exceptions
                        // Devolve 401 quando alguém tentar acessar uma rota protegida sem estar autenticado.
                        .authenticationEntryPoint((request, response, exception) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                )

                // Coloca o filtro na cadeia do Spring Security, antes da posição reservada ao filtro de usuário e senha.
                .addFilterBefore(
                        new BearerAuthenticationFilter(jwtService, userRepository),
                        UsernamePasswordAuthenticationFilter.class
                )
                .addFilterBefore(new AuthCsrfFilter(cors.getAllowedOrigins()), BearerAuthenticationFilter.class)

                // Configuração de rotas
                .authorizeHttpRequests(req -> {
                    // Permite o processamento interno das respostas de erro, sem liberar genericamente qualquer caminho /error/**.
                    req.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();

                    req.requestMatchers(
                            HttpMethod.POST,
                            "/api/auth/register",
                            "/api/auth/login",
                            "/api/auth/refresh",
                            "/api/auth/logout",
                            "/api/auth/resend-activation-email",
                            "/api/auth/forgot-password",
                            "/api/auth/email-change-confirmation",
                            "/api/auth/reset-password"
                    ).permitAll();

                    req.requestMatchers(
                            HttpMethod.GET,
                            "/api/auth/verify",
                            "/api/locations/cities",
                            "/api/spotify/callback"
                    ).permitAll();

                    // O controller exige o token de cadastro nesta rota.
                    req.requestMatchers(
                            HttpMethod.PATCH,
                            "/api/auth/update-pending-email"
                    ).permitAll();

                    // As demais requisições exigem autenticação.
                    req.anyRequest().authenticated();
                })
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
