package br.com.pablohcarmo.sonarfy.repositories;

import br.com.pablohcarmo.sonarfy.entities.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    // Obtém o ID antes do bloqueio sem carregar entidades obsoletas.
    @Query("select t.user.id from RefreshToken t where t.tokenHash = :hash")
    Optional<Long> findUserIdByTokenHash(@Param("hash") String hash);

    // Consulta novamente o token depois de bloquear o usuário.
    @Query("select t from RefreshToken t join fetch t.user where t.tokenHash = :hash")
    Optional<RefreshToken> findWithUserByTokenHash(@Param("hash") String hash);

    // Tokens atuais e consumidos identificam a mesma sessão.
    @Modifying(flushAutomatically = true)
    @Query("delete from RefreshToken t where t.sessionId = :sessionId")
    int deleteBySessionId(@Param("sessionId") UUID sessionId);

    // Preserva o contexto JPA do chamador ao excluir as sessões.
    @Modifying(flushAutomatically = true)
    @Query("delete from RefreshToken t where t.user.id = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);

    long countByUserId(Long userId);
}
