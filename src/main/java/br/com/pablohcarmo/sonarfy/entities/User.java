package br.com.pablohcarmo.sonarfy.entities;

import jakarta.persistence.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

@Entity
@Table (name = "Users")
public class User implements UserDetails {

    @Id
    @GeneratedValue (strategy = GenerationType.IDENTITY)
    @Column (name = "user_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "permission_id", nullable = false)
    private Permission permission;

    @Column (name = "name")
    private String name;

    @Column (name = "surname")
    private String surname;

    @Column (name = "handle", unique = true, nullable = false)
    private String handle;

    @Column (name = "email", unique = true, nullable = false)
    private String email;

    @Column (name = "password_hashed")
    private String password;

    @Column (name = "active")
    private boolean isActive;

    @Column (name = "verified", nullable = false)
    private boolean isVerified = false;

    @Column (name = "city")
    private String city;

    @Column (name = "country")
    private String country;

    @Column (name = "avatar")
    private String avatar;

    @Column (name = "wallpaper")
    private String wallpaper;

    @Column (name = "biography")
    private String biography;

    @Column (name = "birth_date")
    private LocalDate birthDate;

    @Column (name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime creationDate;

    @Column (name = "updated_at")
    private OffsetDateTime lastUpdateDate;

    @Column (name = "password_updated_at")
    private OffsetDateTime passwordLastUpdateDate;

    @Column (name = "last_login_at")
    private OffsetDateTime lastLoginDate;

    public User(Long id,
                Permission permission,
                String name,
                String surname,
                String handle,
                String email,
                String passwordHashed,
                boolean isActive,
                boolean isVerified,
                String city,
                String country,
                String avatar,
                String wallpaper,
                String biography,
                LocalDate birthDate,
                OffsetDateTime creationDate,
                OffsetDateTime lastUpdateDate,
                OffsetDateTime passwordLastUpdateDate,
                OffsetDateTime lastLoginDate
    ) {
        this.id = id;
        this.permission = permission;
        this.name = name;
        this.surname = surname;
        this.handle = handle;
        this.email = email;
        this.password = passwordHashed;
        this.isActive = isActive;
        this.isVerified = isVerified;
        this.city = city;
        this.country = country;
        this.avatar = avatar;
        this.wallpaper = wallpaper;
        this.biography = biography;
        this.birthDate = birthDate;
        this.creationDate = creationDate;
        this.lastUpdateDate = lastUpdateDate;
        this.passwordLastUpdateDate = passwordLastUpdateDate;
        this.lastLoginDate = lastLoginDate;
    }

    public User() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Permission getPermissionId() {
        return permission;
    }

    public void setPermissionId(Permission permission) {
        this.permission = permission;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSurname() {
        return surname;
    }

    public void setSurname(String surname) {
        this.surname = surname;
    }

    public String getHandle() {
        return handle;
    }

    public void setHandle(String handle) {
        this.handle = handle;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean active) {
        isActive = active;
    }

    public boolean isVerified() {
        // Permite o login apenas se o usuário estiver verificado
        return this.isVerified;
    }

    public void setVerified(boolean verified) {
        isVerified = verified;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getCountry() {
        return country;
    }

    public void setCountry(String country) {
        this.country = country;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

    public String getWallpaper() {
        return wallpaper;
    }

    public void setWallpaper(String wallpaper) {
        this.wallpaper = wallpaper;
    }

    public String getBiography() {
        return biography;
    }

    public void setBiography(String biography) {
        this.biography = biography;
    }

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public void setBirthDate(LocalDate birthDate) {
        this.birthDate = birthDate;
    }

    public OffsetDateTime getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(OffsetDateTime creationDate) {
        this.creationDate = creationDate;
    }

    public OffsetDateTime getLastUpdateDate() {
        return lastUpdateDate;
    }

    public void setLastUpdateDate(OffsetDateTime lastUpdateDate) {
        this.lastUpdateDate = lastUpdateDate;
    }

    public OffsetDateTime getPasswordLastUpdateDate() {
        return passwordLastUpdateDate;
    }

    public void setPasswordLastUpdateDate(OffsetDateTime passwordLastUpdateDate) {
        this.passwordLastUpdateDate = passwordLastUpdateDate;
    }

    public OffsetDateTime getLastLoginDate() {
        return lastLoginDate;
    }

    public void setLastLoginDate(OffsetDateTime lastLoginDate) {
        this.lastLoginDate = lastLoginDate;
    }

    @PrePersist
    protected void onCreate() {
        creationDate = OffsetDateTime.now();
        lastUpdateDate = OffsetDateTime.now();
        this.isActive = true;
    }

    @PreUpdate
    protected  void onUpdate() {
        lastUpdateDate = OffsetDateTime.now();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(this.getPermissionId().getName()));
    }

    @Override
    public boolean isAccountNonExpired() {
        return UserDetails.super.isAccountNonExpired();
    }

    @Override
    public boolean isAccountNonLocked() {
        return UserDetails.super.isAccountNonLocked();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return UserDetails.super.isCredentialsNonExpired();
    }

    @Override
    public String getUsername() {
        return this.email;
    }

    @Override
    public boolean isEnabled() {
        // Usuário só pode logar se estiver ativo
        return this.isActive;
    }
}