package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.UpdateProfileDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.dto.UserDto;
import br.com.pablohcarmo.sonarfy.services.UserService;
import br.com.pablohcarmo.sonarfy.services.ProfileService;
import br.com.pablohcarmo.sonarfy.dto.ProfileDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final UserService userService;
    private final ProfileService profileService;

    public ProfileController(UserService userService, ProfileService profileService) {
        this.userService = userService;
        this.profileService = profileService;
    }

    @GetMapping("/me")
    public ResponseEntity<ProfileDto> getCurrentUser(@AuthenticationPrincipal User authenticatedUser) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(profileService.current(authenticatedUser.getId()));
    }

    // The handle selects the viewed profile; the principal alone determines ownership.
    @GetMapping("/by-handle/{handle}")
    public ResponseEntity<ProfileDto> getByHandle(@PathVariable String handle,
                                                 @AuthenticationPrincipal User authenticatedUser) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(profileService.byHandle(handle, authenticatedUser.getId()));
    }

    @PutMapping("/me")
    public ResponseEntity<UserDto>updateProfile(@RequestBody @Valid UpdateProfileDto updateProfileDto, @AuthenticationPrincipal User authenticatedUser) {
        // Envia os dados limpos para a camada de serviço, que vai atualizar o usuário logado e devolver o DTO correspondente
        return ResponseEntity.ok(userService.updateProfile(authenticatedUser.getId(), updateProfileDto));
    }
}
