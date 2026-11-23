package br.com.pablohcarmo.sonarfy.validation;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import java.math.BigDecimal;

public class ReviewRatingDeserializer extends ValueDeserializer<BigDecimal> {
    // Lê o decimal original sem passar por double nem aceitar strings numéricas.
    @Override
    public BigDecimal deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT) && !parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT))
            return context.reportInputMismatch(BigDecimal.class, "Rating must be a JSON number.");
        return parser.getDecimalValue();
    }
}
