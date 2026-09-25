package br.com.pablohcarmo.sonarfy.dto;
import java.time.LocalDate;
import java.time.LocalDateTime;

public class UserDto {
    private Long id;
    private String name;
    private String surname;
    private String handle;
    private String email;
    private boolean isActive;
    private String city;
    private String country;
    private String profilePicture;
    private String wallpaper;
    private String biography;
    private LocalDate birthDate;
    private LocalDateTime creationDate;
    private LocalDateTime lastLoginDate;
    private LocalDateTime lastUpdateDate;

    public UserDto(){
    }

    public UserDto(Long id,
                   String name,
                   String surname,
                   String handle,
                   String email,
                   boolean isActive,
                   String city,
                   String country,
                   String profilePicture,
                   String wallpaper,
                   String biography,
                   LocalDate birthDate,
                   LocalDateTime creationDate,
                   LocalDateTime lastLoginDate,
                   LocalDateTime lastUpdateDate
    ) {
        this.id = id;
        this.name = name;
        this.surname = surname;
        this.handle = handle;
        this.email = email;
        this.isActive = isActive;
        this.city = city;
        this.country = country;
        this.profilePicture = profilePicture;
        this.wallpaper = wallpaper;
        this.biography = biography;
        this.birthDate = birthDate;
        this.creationDate = creationDate;
        this.lastLoginDate = lastLoginDate;
        this.lastUpdateDate = lastUpdateDate;
    }

    public Long getId() {
        return id;
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

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
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

    public String getProfilePicture() {
        return profilePicture;
    }

    public void setProfilePicture(String profilePicture) {
        this.profilePicture = profilePicture;
    }

    public String getWallpaper() {
        return wallpaper;
    }

    public void setWallpaper(String wallpaper) {
        this.wallpaper = wallpaper;
    }

    public String getBiography() {
        return biography;
    }

    public void setBiography(String biography) {
        this.biography = biography;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public LocalDateTime getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(LocalDateTime creationDate) {
        this.creationDate = creationDate;
    }

    public LocalDateTime getLastLoginDate() {
        return lastLoginDate;
    }

    public void setLastLoginDate(LocalDateTime lastLoginDate) {
        this.lastLoginDate = lastLoginDate;
    }

    public LocalDateTime getLastUpdateDate() {
        return lastUpdateDate;
    }

    public void setLastUpdateDate(LocalDateTime lastUpdateDate) {
        this.lastUpdateDate = lastUpdateDate;
    }
}