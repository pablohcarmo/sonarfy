package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyIntegrationException;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SpotifyCallbackService {

    private final SpotifyAuthorizationStateService stateService;
    private final UserRepository userRepository;
    private final AuthSpotifyService authSpotifyService;
    private final SpotifyConnectionService connectionService;

    public SpotifyCallbackService(SpotifyAuthorizationStateService stateService, UserRepository userRepository,
                                  AuthSpotifyService authSpotifyService, SpotifyConnectionService connectionService) {
        this.stateService = stateService;
        this.userRepository = userRepository;
        this.authSpotifyService = authSpotifyService;
        this.connectionService = connectionService;
    }

    public void handleCallback(String code, String state, String error) {
        // Identifica qual resultado chegou: código de autorização ou erro.
        boolean hasCode = code != null && !code.isBlank();
        boolean hasError = error != null && !error.isBlank();

        // Os dois não podem estar presentes nem ausentes ao mesmo tempo, só aceitamos o erro da recusa previsto para este fluxo
        if (hasCode == hasError || (hasError && !"access_denied".equals(error))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid Spotify callback parameters."
            );
        }

        // Valida a tentativa, remove seu state e recupera o usuário associado.
        // Se o state for inválido, o próprio consume lança a exceção.
        var attempt = stateService.consume(state);
        Long userId = attempt.userId();

        // O usuário pode ter sido excluído ou desativado desde o início do fluxo.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "User cannot complete Spotify authorization."
                ));

        // isEnabled verifica se a conta está ativa e com o e-mail confirmado.
        if (!user.isEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "User cannot complete Spotify authorization."
            );
        }

        // Uma recusa também encerra a tentativa: o state já foi consumido, não existe código para trocar por tokens.
        if (hasError) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Spotify authorization was denied."
            );
        }

        try {
            // Guarda os dados devolvidos pela troca do código.
            var response = authSpotifyService.exchangeCode(code);

            // Uma resposta HTTP de sucesso do Spotify ainda pode estar incompleta,
            // só consideramos a troca concluída se os campos essenciais forem válidos.
            SpotifyTokenResponseValidator.validate(response);

            connectionService.connect(userId, attempt.authorizationVersion(), response);

        } catch (SpotifyIntegrationException exception) {
            // Traduz as categorias do serviço para respostas HTTP.
            HttpStatus status = switch (exception.getCategory()) {
                case REJECTED ->  HttpStatus.BAD_GATEWAY;
                case UNAVAILABLE ->  HttpStatus.SERVICE_UNAVAILABLE;
                case TIMEOUT ->  HttpStatus.GATEWAY_TIMEOUT;
            };

            // A mensagem de exceção já é fixa e não contém detalhes do Spotify.
            throw new ResponseStatusException(status, exception.getMessage());
        }
    }

    // Traduz o resultado do callback para um código seguro de redirecionamento ao frontend.
    public String resolveRedirectOutcome(String code, String state, String error) {
        try {
            handleCallback(code, state, error);
            return "connected";
        } catch (ResponseStatusException exception) {
            // Parâmetros inválidos, state expirado/usado ou versão de autorização vencida.
            if (exception.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()) {
                return "invalid_state";
            }
            // Recusa explícita do usuário no Spotify após validar o state e a conta.
            if (exception.getStatusCode().value() == HttpStatus.FORBIDDEN.value()
                    && "Spotify authorization was denied.".equals(exception.getReason())) {
                return "denied";
            }
            return "error";
        } catch (RuntimeException exception) {
            // Falhas inesperadas de persistência ou cifragem não expõem detalhes na URL.
            return "error";
        }
    }
}
