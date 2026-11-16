package br.com.pablohcarmo.sonarfy.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record UserDto(
        Long id,
        String name,
        String surname,
        String handle,
        String email,
        boolean active,
        String city,
        String country,
        String profilePicture,
        String wallpaper,
        String biography,
        LocalDate birthDate,
        LocalDateTime creationDate,
        LocalDateTime lastUpdateDate
) {
}
