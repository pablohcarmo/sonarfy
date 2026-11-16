package br.com.pablohcarmo.sonarfy.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class CorsConfigTest {

    // A lista de confiança aceita somente origens exatas, sem curingas ou caminhos.
    @ParameterizedTest
    @ValueSource(strings = {"*", "https://*.example.com", "http://localhost:63342/pages",
            "http://user@localhost:63342", "http://localhost:63342?x=1", "file:///pages"})
    void rejectsUnsafeOriginConfiguration(String origin) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new CorsConfig(origin));
    }

    static class InspectableCorsRegistry extends CorsRegistry {
        CorsConfiguration configFor(String pattern) {
            return getCorsConfigurations().get(pattern);
        }
    }

    @Test
    @DisplayName("CORS deve permitir PATCH e o header Authorization para o frontend")
    void shouldAllowPatchAndAuthorizationHeader() {
        InspectableCorsRegistry registry = new InspectableCorsRegistry();
        new CorsConfig("http://localhost:63342,http://localhost:8080").addCorsMappings(registry);

        CorsConfiguration config = registry.configFor("/**");

        assertNotNull(config, "Configuração CORS para /** não deve ser nula");
        assertNotNull(config.checkHttpMethod(HttpMethod.PATCH), "PATCH deve ser permitido pelo CORS");
        assertNotNull(config.checkHeaders(List.of("Authorization")), "Header Authorization deve ser permitido pelo CORS");
    }
}
