package br.com.pablohcarmo.sonarfy.dto;

import java.time.LocalDate;

public class UpdateUserDto {
    private String name;
    private String surname;
    private String handle;
    private String city;
    private String country;
    private LocalDate birthDate;

    public UpdateUserDto(String name,
                         String surname,
                         String handle,
                         String city,
                         String country,
                         LocalDate birthDate
    ) {
        this.name = name;
        this.surname = surname;
        this.handle = handle;
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