package br.com.pablohcarmo.sonarfy.repositories;

import br.com.pablohcarmo.sonarfy.entities.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User,Long> {
    Optional<User> findByEmailIgnoreCaseOrHandleIgnoreCase(String email, String handle);

    Optional<User> findByHandleIgnoreCase(String handle);

    Optional<User> findByEmailIgnoreCase(String email);

    // Consulta nativa atualiza diretamente a acoluna protegida contra updates comuns.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query( value = """
            UPDATE Users
                SET  last_email_sent_at = :now
            WHERE user_id = :userId
                AND email = :email
                AND verified = :verified
                AND (last_email_sent_at IS NULL OR last_email_sent_at <= :cutoff)
            """, nativeQuery = true)
    int reserveEmailSend(
            @Param("userId") Long userId,
            @Param("email") String email,
            @Param("verified") boolean verified,
            @Param("now") OffsetDateTime now,
            @Param("cutoff") OffsetDateTime cutoff
    );

    // Serializa alterações de senha até o fim da transação; leituras comuns podem continuar.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForPasswordUpdate(@Param("id") Long id);
}
