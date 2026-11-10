package br.com.pablohcarmo.sonarfy.repositories;

import br.com.pablohcarmo.sonarfy.entities.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    // Carrega o token com o usuário para validar a conta na mesma consulta.
    @Query("SELECT t FROM RefreshToken t JOIN FETCH t.user WHERE t.tokenHash = :hash")
    Optional<RefreshToken> findWithUserByTokenHash(@Param("hash") String hash);

    // Troca o hash e renova a validade somente se o token atual ainda existir e não tiver expirado.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE RefreshToken t
            SET t.tokenHash = :newHash, t.expiresAt = :expiresAt
        WHERE t.tokenHash = :currentHash and t.expiresAt > :now
        """)
    int rotate(
            @Param("currentHash") String currentHash,
            @Param("newHash") String newHash,
            @Param("expiresAt") OffsetDateTime expiresAt,
            @Param("now") OffsetDateTime now);

    // Encerra a sessão de um dispositivo.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM RefreshToken t WHERE t.tokenHash = :hash")
    int deleteByTokenHash(@Param("hash") String hash);

    // Encerra as sessões de todos os dispositivos do usuário.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM RefreshToken t WHERE t.user.id = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);

    long countByUserId(Long userId);
}
