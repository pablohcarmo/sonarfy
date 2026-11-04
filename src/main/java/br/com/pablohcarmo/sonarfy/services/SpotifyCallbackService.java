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

    public SpotifyCallbackService(SpotifyAuthorizationStateService stateService, UserRepository userRepository, AuthSpotifyService authSpotifyService) {
        this.stateService = stateService;
        this.userRepository = userRepository;
        this.authSpotifyService = authSpotifyService;
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
        Long userId = stateService.consume(state);

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
            // Reutiliza a troca já implementada.
            authSpotifyService.exchangeCode(code);
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
}
