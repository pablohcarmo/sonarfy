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
            + "u.city as city, u.country as country, u.creationDate as joinedAt, "
            + "(select count(f) from UserFollowing f, User peer where f.id.followingId = u.id "
            + "and peer.id = f.id.followerId and peer.isActive = true and peer.isVerified = true) as followers, "
            + "(select count(f) from UserFollowing f, User peer where f.id.followerId = u.id "
            + "and peer.id = f.id.followingId and peer.isActive = true and peer.isVerified = true) as following, "
            + "case when u.id <> :actorId and exists (select 1 from UserFollowing f, User actor "
            + "where f.id.followerId = :actorId and f.id.followingId = u.id and actor.id = :actorId "
            + "and actor.isActive = true and actor.isVerified = true) then true else false end as followed "
            + "from User u ";

    // A identidade e os relacionamentos usam o mesmo snapshot, sem carregar coleções ou dados privados.
    @Query(FIELDS + "where u.id = :id and u.isActive = true and u.isVerified = true")
    Optional<View> findVisibleById(@Param("id") Long id, @Param("actorId") Long actorId);

    @Query(FIELDS + "where lower(u.handle) = lower(:handle) and u.isActive = true and u.isVerified = true")
    Optional<View> findVisibleByHandle(@Param("handle") String handle, @Param("actorId") Long actorId);

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
        long getFollowers();
        long getFollowing();
        boolean getFollowed();
    }
}
