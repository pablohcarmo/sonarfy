package br.com.pablohcarmo.sonarfy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Desabilta o CSRF, pois APIs REST baseadas em tokens náo precisam dele
                .csrf(csrf -> csrf.disable())

                // Transforma a API em Stateless (náo guarda a sessão do usuário)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // Configuração de rotas
                .authorizeHttpRequests(req -> {
                    req.requestMatchers(
                            "/api/auth/**",
                            "/verifiy-email/",
                            "/reset-password/**"
                    ).permitAll();

                    req.anyRequest().authenticated();
                })
                .build();

    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
