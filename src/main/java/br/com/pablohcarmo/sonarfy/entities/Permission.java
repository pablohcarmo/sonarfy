package br.com.pablohcarmo.sonarfy.entities;

import jakarta.persistence.*;

@Entity
@Table(name = "Permissions")
public class Permission {
    @Id
    @GeneratedValue (strategy = GenerationType.IDENTITY)
    @Column (name = "permission_id")
    private Long id;

    @Column (name = "permission", unique = true, nullable = false)
    private String name;

    public Permission() {
    }

    public Permission(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}