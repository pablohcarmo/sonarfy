package br.com.pablohcarmo.sonarfy.entities;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table (name = "access_logs")
public class AccessLog {
    @Id
    @GeneratedValue (strategy = GenerationType.IDENTITY)
    @Column (name = "log_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column (name = "ip_address")
    private String ipAddress;

    @Column (name = "logged_at")
    private OffsetDateTime dateTimeAccess;

    public AccessLog() {
    }

    public AccessLog(User user, String ipAddress) {
        this.user = user;
        this.ipAddress = ipAddress;
    }

    @PrePersist
    protected void onCreate() {
        dateTimeAccess = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public OffsetDateTime getDateTimeAccess() {
        return dateTimeAccess;
    }

    public void setDateTimeAccess(OffsetDateTime dateTimeAccess) {
        this.dateTimeAccess = dateTimeAccess;
    }
}
