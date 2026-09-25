package br.com.pablohcarmo.sonarfy.entities;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table (name = "AccessLogs")
public class AccessLog {
    @Id
    @GeneratedValue (strategy = jakarta.persistence.GenerationType.IDENTITY)
    @Column (name = "log_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column (name = "ip_address")
    private String ipAddress;

    @Column (name = "date_time_access")
    private OffsetDateTime dateTimeAccess;

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