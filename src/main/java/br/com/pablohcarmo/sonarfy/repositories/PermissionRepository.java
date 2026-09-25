package br.com.pablohcarmo.sonarfy.repositories;


import br.com.pablohcarmo.sonarfy.entities.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PermissionRepository extends JpaRepository<Permission, Long> {
    Optional<Permission> findByName(String name);
}