package br.com.pablohcarmo.sonarfy.validation;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class ProfileTextDeserializer extends ValueDeserializer<String> {
    // Numbers are valid name characters inside a string, not implicit JSON scalar conversions.
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING))
            return context.reportInputMismatch(String.class, "Profile text must be a JSON string or null.");
        return parser.getString();
    }
}
