package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.client.SpotifyTokenClient;
import br.com.pablohcarmo.sonarfy.dto.SpotifyTokenResponseDto;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyIntegrationException;
import br.com.pablohcarmo.sonarfy.exceptions.SpotifyIntegrationException.Category;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Map;

@Service
public class AuthSpotifyService {

    private final SpotifyTokenClient spotifyTokenClient;

    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public AuthSpotifyService(
            SpotifyTokenClient spotifyTokenClient,
            @Value("${SPOTIFY_CLIENT_ID}") String clientId,
            @Value("${SPOTIFY_CLIENT_SECRET}") String clientSecret,
            @Value("${SPOTIFY_REDIRECT_URI}") String redirectUri
    ) {
        // Valida a configuração na criação do service, antes de aceitar requisições.
        if (clientId == null || clientId.isBlank()
                || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException(
                    "Spotify clientId and clientSecret must be configured."
            );
        }
        validateRedirectUri(redirectUri);

        this.spotifyTokenClient = spotifyTokenClient;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
    }

    // Emite credencial da aplicação sem consentimento ou refresh token de usuário.
    public SpotifyTokenResponseDto clientCredentials() {
        var tokens = requestTokens(Map.of("grant_type", "client_credentials"), false);
        SpotifyTokenResponseValidator.validateRefresh(tokens);
        return tokens;
    }

    // Reutiliza cliente e autenticação, distinguindo a revogação confirmada.
    public SpotifyTokenResponseDto refresh(String refreshToken) {
        return requestTokens(Map.of("grant_type", "refresh_token", "refresh_token", refreshToken), true);
    }

    public SpotifyTokenResponseDto exchangeCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Authorization code is required."
            );
        }

        return requestTokens(Map.of("grant_type", "authorization_code", "code", code,
                "redirect_uri", redirectUri), false);
    }

    private SpotifyTokenResponseDto requestTokens(Map<String, String> form, boolean refresh) {
        var headers = new HttpHeaders();
        headers.setBasicAuth(clientId, clientSecret);

        // Solicita ao Spotify a troca do código de autorização por token
        try {
            return spotifyTokenClient.exchangeCode(
                    headers.getFirst(HttpHeaders.AUTHORIZATION), form);
        } catch (FeignException e) {
            if (refresh && e.status() == 400 && isInvalidGrant(e)) throw new AuthorizationRevoked();
            // Retry-After pode gerar RetryableException até para 4xx: o status tem prioridade.
            if (e.status() >= 400) {
                boolean rejected = e.status() < 500 && e.status() != 429;
                throw new SpotifyIntegrationException(rejected ? Category.REJECTED : Category.UNAVAILABLE);
            }
            // Só interpreta timeout de transporte quando não há status de erro HTTP.
            if (e instanceof RetryableException) {
                for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                        throw new SpotifyIntegrationException(Category.TIMEOUT);
                    }
                }
            }
            throw new SpotifyIntegrationException(Category.UNAVAILABLE);
        }
    }

    static final class AuthorizationRevoked extends RuntimeException {
        AuthorizationRevoked() { super("Spotify reauthorization required."); }
    }

    // Examina somente o código OAuth, sem propagar corpo ou causa externa.
    private static boolean isInvalidGrant(FeignException exception) {
        try {
            var body = tools.jackson.databind.json.JsonMapper.builder().build()
                    .readTree(exception.contentUTF8());
            return body != null && "invalid_grant".equals(body.path("error").asText());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static void validateRedirectUri(String redirectUri) {
        // Impede iniciar a aplicação sem um endereço de retorno configurado.
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new IllegalArgumentException("SPOTIFY_REDIRECT_URI is required.");
        }

        final URI uri;

        // Interpreta a sintaxe da URI sem acessar a rede nem consultar o Spotify.
        try {
            uri = new URI(redirectUri);
        } catch (URISyntaxException e) {
            // Não inclui o endereço informado na mensagem de erro.
            throw new IllegalArgumentException("SPOTIFY_REDIRECT_URI must be a valid URI.");
        }

        String host = uri.getHost();
        String scheme = uri.getScheme();

        // No desenvolvimento, HTTP só é permitido para IPs explícitos de loopback.
        boolean loopback = "127.0.0.1".equals(host)
                || "[::1]".equals(host);

        // Para os demais hosts, exige HTTPS.
        boolean allowedScheme = "https".equalsIgnoreCase(scheme)
                || ("http".equalsIgnoreCase(scheme) && loopback);

        // Exige host e protocolo permitido; rejeita credenciais, fragmento e porta inválida.
        // A correspondência com a URI cadastrada no painel será conferida pelo Spotify.
        if (host == null
                || "localhost".equalsIgnoreCase(host)
                || !allowedScheme
                || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null
                || uri.getPort() > 65535
                || uri.getPort() == 0) {
            throw new IllegalArgumentException(
                    "SPOTIFY_REDIRECT_URI must use HTTPS, or HTTP with an explicit loopback IP, "
                            + "without user information or a fragment, and with a valid port."
            );
        }
    }
}
