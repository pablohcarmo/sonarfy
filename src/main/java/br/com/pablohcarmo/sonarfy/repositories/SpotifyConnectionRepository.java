package br.com.pablohcarmo.sonarfy.repositories;

import br.com.pablohcarmo.sonarfy.entities.SpotifyConnection;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SpotifyConnectionRepository extends JpaRepository<SpotifyConnection, Long> {
    Optional<SpotifyConnection> findByUserId(Long userId);
    long countByUserId(Long userId);

    // Busca e bloqueia um lote de conexões com ao menos um token fora da chave ativa.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select connection
            from SpotifyConnection connection
            where connection.accessTokenEncrypted not like :activePattern
               or connection.refreshTokenEncrypted not like :activePattern
            order by connection.id
            """)
    List<SpotifyConnection> findNeedingTokenReencryption(
            @Param("activePattern") String activePattern,
            Pageable pageable
    );

    // Conta conexões que ainda têm ao menos um token fora da chave ativa.
    @Query("""
            select count(connection)
            from SpotifyConnection connection
            where connection.accessTokenEncrypted not like :activePattern
               or connection.refreshTokenEncrypted not like :activePattern
            """)
    long countNeedingTokenReencryption(@Param("activePattern") String activePattern);

    // Conta tokens de acesso armazenados com o prefixo informado.
    long countByAccessTokenEncryptedStartingWith(String prefix);

    // Conta tokens de atualização armazenados com o prefixo informado.
    long countByRefreshTokenEncryptedStartingWith(String prefix);
}
