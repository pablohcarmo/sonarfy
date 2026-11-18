package br.com.pablohcarmo.sonarfy.validation;

import java.time.LocalDate;
import java.time.Period;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public final class PersonalDataRules {
    public static final String NAME = "^[A-Za-zÀ-ÖØ-öø-ÿ]+(?:['’-][A-Za-zÀ-ÖØ-öø-ÿ]+)*(?:\\s[A-Za-zÀ-ÖØ-öø-ÿ]+(?:['’-][A-Za-zÀ-ÖØ-öø-ÿ]+)*)*$";
    public static final String HANDLE = "^[a-zA-Z0-9_.-]+$";
    public static final String LOCATION = "^[a-zA-ZÀ-ÿ\\s.'-]+$";

    private PersonalDataRules() {}

    // Cadastro e edição usam a mesma representação, independente do idioma do servidor.
    public static String normalizeHandle(String handle) {
        return handle == null ? null : handle.toLowerCase(Locale.ROOT);
    }

    // Cadastro e edição exigem pelo menos 13 anos completos.
    public static void validateBirthDate(LocalDate birthDate) {
        if (birthDate == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Birth date is required.");
        }
        if (Period.between(birthDate, LocalDate.now()).getYears() < 13) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User must be at least 13 years old.");
        }
    }

    // O nome de exibição do perfil usa até 60 pontos de código, sem cortar pares substitutos.
    public static String displayName(String name, String surname) {
        String value = name.strip() + " " + surname.strip();
        int length = Math.min(60, value.codePointCount(0, value.length()));
        return value.substring(0, value.offsetByCodePoints(0, length));
    }
}
