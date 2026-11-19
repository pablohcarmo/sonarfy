package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.ProfileDto;
import br.com.pablohcarmo.sonarfy.repositories.ProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {
    private final ProfileRepository profiles;

    public ProfileService(ProfileRepository profiles) { this.profiles = profiles; }

    // Ownership comes from the authenticated ID, independently of mutable account identifiers.
    public ProfileDto current(Long actorId) {
        return map(profiles.findVisibleById(actorId).orElseThrow(ProfileService::notFound), actorId);
    }

    public ProfileDto byHandle(String handle, Long actorId) {
        return map(profiles.findVisibleByHandle(handle).orElseThrow(ProfileService::notFound), actorId);
    }

    private ProfileDto map(ProfileRepository.View profile, Long actorId) {
        boolean self = profile.getId().equals(actorId);
        return new ProfileDto(new ProfileDto.Identity(profile.getId(), profile.getHandle(), profile.getDisplayName(),
                profile.getAvatar(), profile.getWallpaper(), profile.getBiography(),
                new ProfileDto.Location(profile.getCity(), profile.getCountry()), profile.getJoinedAt()),
                new ProfileDto.Viewer(self, self));
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile not found.");
    }
}
