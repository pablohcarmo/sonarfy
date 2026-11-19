package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.ProfileDto;
import br.com.pablohcarmo.sonarfy.repositories.ProfileRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.dto.UpdateProfileDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProfileService {
    private final ProfileRepository profiles;
    private final UserRepository users;
    private final EntityManager entityManager;

    public ProfileService(ProfileRepository profiles, UserRepository users, EntityManager entityManager) {
        this.profiles = profiles;
        this.users = users;
        this.entityManager = entityManager;
    }

    // Reload under the existing account lock to preserve concurrent password and email changes.
    @Transactional
    public ProfileDto update(Long actorId, UpdateProfileDto input) {
        String name = input.getDisplayName();
        if (input.hasDisplayName()) {
            name = name == null ? null : name.replaceAll("(?U)^\\s+|\\s+$", "");
            if (name == null || name.isEmpty() || name.codePointCount(0, name.length()) > 60)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Display name must contain 1 to 60 Unicode code points.");
        }
        String bio = input.getBiography();
        if (bio != null && bio.codePointCount(0, bio.length()) > 150)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Biography must contain at most 150 Unicode code points.");
        var user = users.findById(actorId).orElseThrow(ProfileService::notFound);
        entityManager.refresh(user, LockModeType.PESSIMISTIC_WRITE);
        if (!user.isEnabled()) throw notFound();
        if (input.hasDisplayName()) user.setDisplayName(name);
        if (input.hasBiography()) user.setBiography(bio == null || bio.isEmpty() ? null : bio);
        users.saveAndFlush(user);
        return current(actorId);
    }

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
