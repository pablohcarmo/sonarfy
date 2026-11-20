package br.com.pablohcarmo.sonarfy.entities;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Immutable;
import java.io.Serializable;
import java.time.OffsetDateTime;

// Mapeia a relação para projeções; as mutações continuam no serviço transacional.
@Entity
@Table(name = "user_following")
@Immutable
public class UserFollowing {
    @EmbeddedId
    private Key id;

    @Column(name = "follow_date", nullable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private OffsetDateTime followDate;

    @Embeddable
    public record Key(@Column(name = "follower_id") Long followerId,
                      @Column(name = "following_id") Long followingId) implements Serializable {}
}
