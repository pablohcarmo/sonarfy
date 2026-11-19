package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.UpdateProfileDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.services.ProfileService;
import br.com.pablohcarmo.sonarfy.dto.ProfileDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileService profileService;

    public ProfileController(ProfileService profileService) {
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

    // Text edits use only the authenticated identity and return the same safe representation as GET.
    @PutMapping("/me")
    public ResponseEntity<ProfileDto> updateProfile(@RequestBody UpdateProfileDto input,
                                                   @AuthenticationPrincipal User authenticatedUser) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(profileService.update(authenticatedUser.getId(), input));
    }
}
