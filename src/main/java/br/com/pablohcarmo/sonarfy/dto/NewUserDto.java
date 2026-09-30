package br.com.pablohcarmo.sonarfy.dto;

import jakarta.validation.constraints.*;

import java.time.LocalDate;

// TODO = incluir anotações de validação para cada campo, como @NotNull, @Size, @Email, etc.

public class NewUserDto {
    @NotBlank(message = "Name is required")
    @Pattern(regexp = "^[a-zA-ZÀ-ÿ\\s]+$", message = "Name must contain only letters and spaces")
    private String name;

    @NotBlank(message = "Surname is required")
    @Pattern(regexp = "^[a-zA-ZÀ-ÿ\\s]+$", message = "Surname must contain only letters and spaces")
    private String surname;

    @NotBlank(message = "Handle is required")
    @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "Handle must contain only letters, numbers, underscores, hyphens and dots")
    private String handle;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters long")
    private String password;

    @NotBlank(message = "City is required")
    private String city;

    @NotBlank(message = "Country is required")
    private String country;

    @Past(message = "Birth date must be in the past")
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