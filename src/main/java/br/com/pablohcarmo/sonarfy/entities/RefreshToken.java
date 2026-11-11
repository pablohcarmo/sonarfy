package br.com.pablohcarmo.sonarfy.entities;

import jakarta.persistence.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy =  GenerationType.IDENTITY)
    @Column(name = "token_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "token_hash", length = 64, nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "remember_me", nullable = false)
    private boolean rememberMe;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    public RefreshToken() {
    }

    public RefreshToken(User user, String tokenHash, OffsetDateTime expiresAt) {
        this(user, tokenHash, expiresAt, UUID.randomUUID());
    }

    public RefreshToken(User user, String tokenHash, OffsetDateTime expiresAt, UUID sessionId) {
        this(user, tokenHash, expiresAt, sessionId, false);
    }

    public RefreshToken(User user, String tokenHash, OffsetDateTime expiresAt, UUID sessionId, boolean rememberMe) {
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.sessionId = sessionId;
        this.rememberMe = rememberMe;
    }

    public UUID getSessionId() { return sessionId; }
    public boolean isRememberMe() { return rememberMe; }
    public OffsetDateTime getUsedAt() { return usedAt; }

    // Conserva o hash consumido para reconhecer reutilizações da mesma sessão.
    public void markUsed(OffsetDateTime now) { usedAt = now; }

    @PrePersist
    protected void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public User getUser() {
        return user;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(OffsetDateTime expiresAt) {
        this.expiresAt = expiresAt;
    }
}
