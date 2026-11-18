package br.com.pablohcarmo.sonarfy.validation;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

public class BirthDateDeserializer extends ValueDeserializer<LocalDate> {
    // Aceita somente datas existentes em string ISO, sem coerção de arrays ou horários.
    @Override
    public LocalDate deserialize(JsonParser parser, DeserializationContext context) {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return context.reportInputMismatch(LocalDate.class, "Birth date must be a YYYY-MM-DD string.");
        }
        String value = parser.getString();
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            return context.reportInputMismatch(LocalDate.class, "Birth date must use YYYY-MM-DD.");
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return context.reportInputMismatch(LocalDate.class, "Birth date must be a valid calendar date.");
        }
    }
}
