package br.com.pablohcarmo.sonarfy.config;

import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.security.BearerAuthenticationFilter;
import br.com.pablohcarmo.sonarfy.services.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService, UserRepository userRepository) throws Exception {
        return http
                // Desabilta o CSRF, pois APIs REST baseadas em tokens náo precisam dele
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

                // Configuração de rotas
                .authorizeHttpRequests(req -> {
                    req.requestMatchers(
                            "/api/auth/**", // Libera todos os endpoints de autenticação (registro e login)
                            "/error/**" // Libera todos os endpoints de erro (para exibir mensagens de erro)
                    ).permitAll();

                    // Qualquer outra rota do sistema exigirá um JWT válido para acesso
                    req.anyRequest().authenticated();
                })
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
