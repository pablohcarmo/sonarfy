package br.com.pablohcarmo.sonarfy.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {
    private final java.util.List<String> origins;

    public CorsConfig(@Value("${app.cors.allowed-origins:${AUTH_ALLOWED_ORIGINS:http://localhost:63342,http://localhost:8080}}") String origins) {
        this.origins = java.util.Arrays.stream(origins.split(",")).map(String::trim).toList();
        for (String origin : this.origins) {
            var uri = java.net.URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || origin.contains("*")) {
                throw new IllegalArgumentException("CORS requires exact HTTP origins without paths or wildcards");
            }
        }
    }

    public java.util.List<String> getAllowedOrigins() { return origins; }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(origins.toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Retry-After")
                .allowCredentials(true);
    }
}
