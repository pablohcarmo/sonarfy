package br.com.pablohcarmo.sonarfy.repositories;

import br.com.pablohcarmo.sonarfy.entities.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User,Long> {
    Optional<User> findByEmailIgnoreCaseOrHandleIgnoreCase(String email, String handle);

    Optional<User> findByHandleIgnoreCase(String handle);

    Optional<User> findByEmailIgnoreCase(String email);
}