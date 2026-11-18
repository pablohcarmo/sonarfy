package br.com.pablohcarmo.sonarfy.dto;

import br.com.pablohcarmo.sonarfy.validation.PersonalDataRules;
import br.com.pablohcarmo.sonarfy.validation.CityIdDeserializer;
import br.com.pablohcarmo.sonarfy.validation.BirthDateDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record NewUserDto(
        @NotBlank(message = "Name is required") @Size(max = 50)
        @Pattern(regexp = PersonalDataRules.NAME, message = "Name must contain only letters and spaces") String name,
        @NotBlank(message = "Surname is required") @Size(max = 50)
        @Pattern(regexp = PersonalDataRules.NAME, message = "Surname must contain only letters and spaces") String surname,
        @NotBlank(message = "Handle is required") @Size(max = 15)
        @Pattern(regexp = PersonalDataRules.HANDLE, message = "Handle must contain only letters, numbers, underscores, hyphens and dots") String handle,
        @NotBlank(message = "Email is required") @Email(message = "Email must be a valid email address") String email,
        @NotBlank(message = "Password is required") @Size(min = 8, message = "Password must be at least 8 characters long") String password,
        @NotNull @Positive @JsonDeserialize(using = CityIdDeserializer.class) Long cityId,
        @NotNull(message = "Birth date is required") @Past(message = "Birth date must be in the past")
        @JsonDeserialize(using = BirthDateDeserializer.class) LocalDate birthDate
) {}
