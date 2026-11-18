package br.com.pablohcarmo.sonarfy.dto;

import br.com.pablohcarmo.sonarfy.validation.BirthDateDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import br.com.pablohcarmo.sonarfy.validation.PersonalDataRules;

import java.time.LocalDate;

public class NewUserDto {
    @NotBlank(message = "Name is required")
    @Pattern(
            regexp = PersonalDataRules.NAME,
            message = "Name must contain only letters and spaces")
    @Size(max = 50)
    private String name;

    @NotBlank(message = "Surname is required")
    @Pattern(
            regexp = PersonalDataRules.NAME,
            message = "Surname must contain only letters and spaces")
    @Size(max = 50)
    private String surname;

    @NotBlank(message = "Handle is required")
    @Pattern(regexp = PersonalDataRules.HANDLE, message = "Handle must contain only letters, numbers, underscores, hyphens and dots")
    @Size(max = 15)
    private String handle;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters long")
    private String password;

    @NotBlank(message = "City is required")
    @Pattern(regexp = PersonalDataRules.LOCATION, message = "City must contain only letters, spaces, hyphens and dots")
    @Size(max = 100)
    private String city;

    @NotBlank(message = "Country is required")
    @Pattern(regexp = PersonalDataRules.LOCATION, message = "Country must contain only letters, spaces, hyphens and dots")
    @Size(max = 100)
    private String country;

    @NotNull(message = "Birth date is required")
    @Past(message = "Birth date must be in the past")
    @JsonDeserialize(using = BirthDateDeserializer.class)
    private LocalDate birthDate;

    public NewUserDto() {
    }

    public NewUserDto(String name,
                      String surname,
                      String handle,
                      String email,
                      String password,
                      String city,
                        String country,
                      LocalDate birthDate
    ) {
        this.name = name;
        this.surname = surname;
        this.handle = handle;
        this.email = email;
        this.password = password;
        this.city = city;
        this.country = country;
        this.birthDate = birthDate;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSurname() {
        return surname;
    }

    public void setSurname(String surname) {
        this.surname = surname;
    }

    public String getHandle() {
        return handle;
    }

    public void setHandle(String handle) {
        this.handle = handle;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }
}
