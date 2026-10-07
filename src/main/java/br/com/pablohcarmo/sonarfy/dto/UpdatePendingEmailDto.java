package br.com.pablohcarmo.sonarfy.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public class UpdatePendingEmailDto {
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    private String newEmail;

    public UpdatePendingEmailDto() {
    }

    public UpdatePendingEmailDto(String newEmail) {
        this.newEmail = newEmail;
    }

    public String getNewEmail() {
        return newEmail;
    }

    public void setNewEmail(String newEmail) {
        this.newEmail = newEmail;
    }

}
