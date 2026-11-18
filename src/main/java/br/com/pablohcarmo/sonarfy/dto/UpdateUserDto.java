package br.com.pablohcarmo.sonarfy.dto;

import br.com.pablohcarmo.sonarfy.validation.BirthDateDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import br.com.pablohcarmo.sonarfy.validation.PersonalDataRules;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record UpdateUserDto(
        @NotBlank @Size(max = 50) @Pattern(regexp = PersonalDataRules.NAME) String name,
        @NotBlank @Size(max = 50) @Pattern(regexp = PersonalDataRules.NAME) String surname,
        @NotBlank @Size(max = 15) @Pattern(regexp = PersonalDataRules.HANDLE) String handle,
        @NotBlank @Size(max = 100) @Pattern(regexp = PersonalDataRules.LOCATION) String city,
        @NotBlank @Size(max = 100) @Pattern(regexp = PersonalDataRules.LOCATION) String country,
        @NotNull @Past
        @JsonDeserialize(using = BirthDateDeserializer.class)
        LocalDate birthDate
) {}
