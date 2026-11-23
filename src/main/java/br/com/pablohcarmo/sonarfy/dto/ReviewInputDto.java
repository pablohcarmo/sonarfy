package br.com.pablohcarmo.sonarfy.dto;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.annotation.JsonDeserialize;
import br.com.pablohcarmo.sonarfy.validation.ReviewRatingDeserializer;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;

public record ReviewInputDto(@JsonDeserialize(using = ReviewRatingDeserializer.class) BigDecimal rating,
                             JsonNode reviewText) {
    // Valida tipos JSON sem coerção e preserva a precisão antes da coluna NUMERIC.
    public BigDecimal validatedRating() {
        if (rating == null) throw invalid();
        BigDecimal value = rating;
        if (value.signum() < 0 || value.compareTo(BigDecimal.TEN) > 0
                || value.remainder(new BigDecimal("0.5")).signum() != 0) throw invalid();
        return value;
    }

    public String normalizedText() {
        if (reviewText == null || reviewText.isNull()) return null;
        if (!reviewText.isTextual()) throw invalid();
        String text = reviewText.asText().replaceAll("(?U)^\\s+|\\s+$", "");
        if (text.codePointCount(0, text.length()) > 255 || text.indexOf('\0') >= 0) throw invalid();
        return text.isEmpty() ? null : text;
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Rating must be 0 to 10 in steps of 0.5; reviewText must be a string of at most 255 characters or null.");
    }
}
