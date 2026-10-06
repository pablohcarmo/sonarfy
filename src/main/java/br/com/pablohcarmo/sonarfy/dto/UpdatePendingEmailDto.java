package br.com.pablohcarmo.sonarfy.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class UpdatePendingEmailDto {
    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid email address")
    private String newEmail;

    @NotBlank(message = "Handle is required")
    @Pattern(regexp = "^@?[a-zA-Z0-9_.-]+$", message = "Handle must contain only letters, numbers, underscores, hyphens and dots")
    private String handle;

    public UpdatePendingEmailDto() {
    }

    public UpdatePendingEmailDto(String handle, String newEmail) {
        this.handle = handle;
        this.newEmail = newEmail;
    }

    public String getNewEmail() {
        return newEmail;
    }

    public void setNewEmail(String newEmail) {
        this.newEmail = newEmail;
    }

    public String getHandle() {
        return handle;
    }

    public void setHandle(String handle) {
        this.handle = handle;
    }
}
