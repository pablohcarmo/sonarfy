package br.com.pablohcarmo.sonarfy.repositories;

import br.com.pablohcarmo.sonarfy.entities.User;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.time.OffsetDateTime;
import java.util.Optional;

public interface ProfileRepository extends Repository<User, Long> {
    String FIELDS = "select u.id as id, u.handle as handle, u.displayName as displayName, "
            + "u.avatar as avatar, u.wallpaper as wallpaper, u.biography as biography, "
            + "u.city as city, u.country as country, u.creationDate as joinedAt from User u ";

    // Select only profile fields; account secrets and permission associations are never projected.
    @Query(FIELDS + "where u.id = :id and u.isActive = true and u.isVerified = true")
    Optional<View> findVisibleById(@Param("id") Long id);

    @Query(FIELDS + "where lower(u.handle) = lower(:handle) and u.isActive = true and u.isVerified = true")
    Optional<View> findVisibleByHandle(@Param("handle") String handle);

    interface View {
        Long getId();
        String getHandle();
        String getDisplayName();
        String getAvatar();
        String getWallpaper();
        String getBiography();
        String getCity();
        String getCountry();
        OffsetDateTime getJoinedAt();
    }
}
