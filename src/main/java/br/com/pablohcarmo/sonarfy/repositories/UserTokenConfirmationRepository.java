package br.com.pablohcarmo.sonarfy.repositories;

import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.entities.UserTokenConfirmation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserTokenConfirmationRepository extends JpaRepository<UserTokenConfirmation, Long> {
    List<UserTokenConfirmation> findByUuid(UUID uuid);
    List<UserTokenConfirmation> findByUserId(Long userId);
    Optional<UserTokenConfirmation> findByUser(User user);
}
