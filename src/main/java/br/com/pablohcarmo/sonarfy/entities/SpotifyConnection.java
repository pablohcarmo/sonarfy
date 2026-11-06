package br.com.pablohcarmo.sonarfy.entities;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "spotify_connection")
@JsonIgnoreType
public class SpotifyConnection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "connection_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "access_token_encrypted", nullable = false, columnDefinition = "TEXT")
    private String accessTokenEncrypted;
    @Column(name = "refresh_token_encrypted", nullable = false, columnDefinition = "TEXT")
    private String refreshTokenEncrypted;
    @Column(name = "token_type", nullable = false)
    private String tokenType;
    @Column(name = "scopes", columnDefinition = "TEXT")
    private String scopes;
    @Column(name = "access_token_expires_at", nullable = false)
    private OffsetDateTime accessTokenExpiresAt;
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "connected_at", nullable = false, updatable = false)
    private OffsetDateTime connectedAt;

    protected SpotifyConnection() {}

    public SpotifyConnection(User user, OffsetDateTime connectedAt) {
        this.user = user;
        this.connectedAt = connectedAt;
    }

    public void updateTokens(String accessTokenEncrypted, String refreshTokenEncrypted,
                             String tokenType, String scopes, OffsetDateTime expiresAt,
                             OffsetDateTime updatedAt) {
        this.accessTokenEncrypted = accessTokenEncrypted;
        this.refreshTokenEncrypted = refreshTokenEncrypted;
        this.tokenType = tokenType;
        this.scopes = scopes;
        this.accessTokenExpiresAt = expiresAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getAccessTokenEncrypted() { return accessTokenEncrypted; }
    public String getRefreshTokenEncrypted() { return refreshTokenEncrypted; }
    public String getTokenType() { return tokenType; }
    public String getScopes() { return scopes; }
    public OffsetDateTime getAccessTokenExpiresAt() { return accessTokenExpiresAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public OffsetDateTime getConnectedAt() { return connectedAt; }

    @Override
    public String toString() { return "SpotifyConnection[hidden tokens]"; }
}
