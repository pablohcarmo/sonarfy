package br.com.pablohcarmo.sonarfy.repositories;

import br.com.pablohcarmo.sonarfy.entities.SpotifyConnection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SpotifyConnectionRepository extends JpaRepository<SpotifyConnection, Long> {
    Optional<SpotifyConnection> findByUserId(Long userId);
    long countByUserId(Long userId);
}
