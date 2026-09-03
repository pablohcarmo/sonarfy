package br.com.pablohcarmo.sonarfy.dto;

import java.time.LocalDate;

// TODO = incluir anotações de validação para cada campo, como @NotNull, @Size, @Email, etc.

public class NewUserDto {
    private String name;
    private String surname;
    private String handle;
    private String email;
    private String password;
    private LocalDate birthDate;
    public NewUserDto() {
    }

    public NewUserDto(String name,
                      String surname,
                      String handle,
                      String email,
                      String password,
                      LocalDate birthDate
    ) {
        this.name = name;
        this.surname = surname;
        this.handle = handle;
        this.email = email;
        this.password = password;
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

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }
}