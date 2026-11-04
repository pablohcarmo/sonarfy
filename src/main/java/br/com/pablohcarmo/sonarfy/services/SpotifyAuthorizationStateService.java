package br.com.pablohcarmo.sonarfy.services;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
public class SpotifyAuthorizationStateService {

    private static final Duration VALIDITY = Duration.ofMinutes(5);
    private static final int MAX_PENDING_STATES = 1000;
    private static final int MAX_PENDING_STATES_PER_USER = 3;

    // Gera states imprevisíveis.
    private final SecureRandom random = new SecureRandom();

    private final Map<String, PendingAuthorization> pending = new HashMap<>();

    // Permite controlar o tempo nos testes.
    private final Clock clock;

    public SpotifyAuthorizationStateService() {
        this(Clock.systemUTC());
    }

    // Os testes podem fornecer um relógio controlado.
    SpotifyAuthorizationStateService(Clock clock) {
        this.clock = clock;
    }

    // Serializa o acesso ao mapa nesta instância.
    public synchronized String create(Long userId) {
        // O ID deverá vir da identidade autenticada.
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("A valid user ID is required.");
        }

        Instant now = clock.instant();

        // Remove registros vencidos antes de criar uma tentativa.
        pending.entrySet().removeIf(
                entry -> !entry.getValue().expiresAt().isAfter(now)
        );

        // Conta somente as tentativas ainda válidas do usuário.
        long userPendingStates = pending.values().stream()
                .filter(pendingAuthorization -> pendingAuthorization.userId().equals(userId))
                .count();

        // Impede que o usuário acumule mais de três autorizações pendentes.
        // A exceção encerra o método antes de gerar ou armazenar outro state.
        if(userPendingStates >= MAX_PENDING_STATES_PER_USER) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many pending Spotify authorizations for this user."
            );
        }

        // Limita o número de tentativas em memória.
        if (pending.size() >= MAX_PENDING_STATES) {
            throw new IllegalStateException(
                    "Too many pending Spotify authorizations."
            );
        }

        String state;

        // Repete a geração em caso de colisão.
        do {
            // Usa 256 bits de aleatoriedade.
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);

            // Codifica para URL, sem padding; não é criptografia.
            state = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(bytes);
        } while (pending.containsKey(state));

        // Associa o state ao usuário com validade de cinco minutos.
        pending.put(
                state,
                new PendingAuthorization(userId, now.plus(VALIDITY))
        );

        // O callback valida e consome este state uma única vez.
        return state;
    }

    // Sincroniza o consumo com a criação e com outras chamadas de consumo.
    public synchronized Long consume(String state) {
        // Sem state, não conseguimos identificar a tentativa de autorização.
        if (state == null || state.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid or expired Spotify authorization state."
            );
        }

        // Busca e remove a tentativa na mesma operação, uma segunda chamada com este state não encontrará mais o registro.
        PendingAuthorization authorization = pending.remove(state);

        // No instante exato da expiração, a tentativa já é inválida
        if (authorization == null || !authorization.expiresAt().isAfter(clock.instant())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid or expired Spotify authorization state."
            );
        }

        // Recupera a identidade registrada no início da autorização, o ID não vem dos parâmetros enviados ao callback.
        return authorization.userId();
    }

    // Dados imutáveis da tentativa pendente.
    private record PendingAuthorization(
            Long userId,
            Instant expiresAt
    ) {
    }
}
