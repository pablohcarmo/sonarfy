package br.com.pablohcarmo.sonarfy.entities;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "EmailConfirmationTokens")
public class UserTokenConfirmation {

    @Id
    @GeneratedValue (strategy = GenerationType.IDENTITY)
    @Column (name = "token_id")
    private Long id;

    @ManyToOne
    @JoinColumn (name = "user_id", nullable = false, referencedColumnName = "user_id", unique = false)
    private User user;

    @Column (name = "uuid", nullable = false, unique = true)
    private UUID uuid;

    @Column (name = "is_used", nullable = false)
    private Boolean isUsed;

    @Column (name = "sent_at", nullable = false)
    private Instant sentAt;

    @Column (name = "expires_at", nullable = false)
    private Instant expiresAt;

    public UserTokenConfirmation() {
    }

    @PrePersist
    protected void onCreate(){
        if(this.uuid == null) {
            this.uuid = UUID.randomUUID();
        }
            this.isUsed = false;
        this.sentAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public UUID getUuid() {
        return uuid;
    }

    public void setUuid(UUID uuid) {
        this.uuid = uuid;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Boolean getUsed() {
        return isUsed;
    }

    public void setUsed(Boolean used) {
        isUsed = used;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}