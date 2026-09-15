package br.com.pablohcarmo.sonarfy.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public class UpdateProfileDto {

    @Size (max = 255, message = "Biography must be at most 255 characters")
    private String biography;
    private String avatar;
    private String wallpaper;

    public UpdateProfileDto() {
    }

    public UpdateProfileDto(String biography,
                            String avatar,
                            String wallpaper) {
        this.biography = biography;
        this.avatar = avatar;
        this.wallpaper = wallpaper;
    }

    public String getBiography() {
        return biography;
    }

    public void setBiography(String biography) {
        this.biography = biography;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getWallpaper() {
        return wallpaper;
    }

    public void setWallpaper(String wallpaper) {
        this.wallpaper = wallpaper;
    }
}