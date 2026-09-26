package br.com.pablohcarmo.sonarfy.dto;

public class UserSummaryDto {
    private Long id;
    private String name;
    private String surname;
    private String handle;
    private String profilePicture;

    public UserSummaryDto() {
    }

    public UserSummaryDto(Long id, String name, String surname, String handle, String profilePicture) {
        this.id = id;
        this.name = name;
        this.surname = surname;
        this.handle = handle;
        this.profilePicture = profilePicture;
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

    public String getProfilePicture() {
        return profilePicture;
    }

    public void setProfilePicture(String profilePicture) {
        this.profilePicture = profilePicture;
    }
}
