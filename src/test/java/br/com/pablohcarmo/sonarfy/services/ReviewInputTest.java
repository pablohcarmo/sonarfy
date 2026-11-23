package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.ReviewInputDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ReviewInputTest {
    // Nenhum valor inválido pode ser arredondado ou convertido implicitamente para nota válida.
    @ParameterizedTest @ValueSource(strings = {"null", "-0.5", "10.5", "8.25", "8.499999",
            "8.4999999999999999999999", "10.0000000000000000000001"})
    void rejectsInvalidRating(String value) {
        var input = new JsonMapper().readValue("{\"rating\":" + value + "}", ReviewInputDto.class);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, input::validatedRating);
    }

    // Tipos incompatíveis são rejeitados já durante a desserialização do contrato HTTP.
    @ParameterizedTest @ValueSource(strings = {"\"8\"", "true", "{}", "[]"})
    void rejectsCoercion(String value) {
        assertThrows(tools.jackson.databind.DatabindException.class, () ->
                new JsonMapper().readValue("{\"rating\":" + value + "}", ReviewInputDto.class));
    }

    // Aceita os 21 passos e normaliza texto ausente, null e espaços Unicode para null.
    @Test void acceptsAllStepsAndNormalizesText() {
        for (int step = 0; step <= 20; step++) {
            var input = new JsonMapper().readValue("{\"rating\":" + (step / 2.0) + "}", ReviewInputDto.class);
            assertEquals(step / 2.0, input.validatedRating().doubleValue());
            assertNull(input.normalizedText());
        }
        for (String text : new String[]{"null", "\"\"", "\"  \\t\\n\\u00a0\""})
            assertNull(new JsonMapper().readValue("{\"reviewText\":" + text + "}", ReviewInputDto.class).normalizedText());
        var nul = new JsonMapper().readValue("{\"reviewText\":\"\\u0000\"}", ReviewInputDto.class);
        assertThrows(org.springframework.web.server.ResponseStatusException.class, nul::normalizedText);
    }
}
