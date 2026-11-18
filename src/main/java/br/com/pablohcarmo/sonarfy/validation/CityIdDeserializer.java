package br.com.pablohcarmo.sonarfy.validation;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class CityIdDeserializer extends ValueDeserializer<Long> {
    // Evita selecionar outra cidade ao truncar decimais ou converter texto em número.
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
            return context.reportInputMismatch(Long.class, "cityId must be an integer.");
        }
        return parser.getLongValue();
    }
}
