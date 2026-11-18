package br.com.pablohcarmo.sonarfy.validation;

import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BirthDateRulesTest {
    // Para nascimento em 29/02, a idade completa muda em 01/03 nos anos não bissextos.
    @ParameterizedTest
    @CsvSource({"2012-02-29,2025-02-28,false", "2012-02-29,2025-03-01,true",
            "2008-02-29,2020-02-29,false", "2008-02-29,2024-02-29,true",
            "2013-11-17,2026-11-16,false", "2013-11-17,2026-11-17,true",
            "2013-11-17,2026-11-18,true"})
    void validatesCompletedYearsWithFixedReferenceDate(String birthValue, String referenceValue, boolean accepted) {
        LocalDate birth = LocalDate.parse(birthValue);
        LocalDate reference = LocalDate.parse(referenceValue);
        try (var dates = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
            dates.when(LocalDate::now).thenReturn(reference);
            if (accepted) {
                assertDoesNotThrow(() -> PersonalDataRules.validateBirthDate(birth));
            } else {
                var error = assertThrows(ResponseStatusException.class,
                        () -> PersonalDataRules.validateBirthDate(birth));
                assertEquals(400, error.getStatusCode().value());
            }
        }
    }
}
