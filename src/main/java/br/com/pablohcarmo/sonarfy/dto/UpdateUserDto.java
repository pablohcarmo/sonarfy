package br.com.pablohcarmo.sonarfy.dto;

import br.com.pablohcarmo.sonarfy.validation.PersonalDataRules;
import br.com.pablohcarmo.sonarfy.validation.CityIdDeserializer;
import br.com.pablohcarmo.sonarfy.validation.BirthDateDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record UpdateUserDto(
        @NotBlank @Size(max = 50) @Pattern(regexp = PersonalDataRules.NAME) String name,
        @NotBlank @Size(max = 50) @Pattern(regexp = PersonalDataRules.NAME) String surname,
        @NotBlank @Size(max = 15) @Pattern(regexp = PersonalDataRules.HANDLE) String handle,
        @Positive @JsonDeserialize(using = CityIdDeserializer.class) Long cityId,
        @NotNull @Past @JsonDeserialize(using = BirthDateDeserializer.class) LocalDate birthDate
) {}
