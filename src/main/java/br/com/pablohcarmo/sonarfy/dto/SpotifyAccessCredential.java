package br.com.pablohcarmo.sonarfy.dto;

// Carrega o token ativo e o carimbo da conexão para validar gravações após a chamada remota.
public record SpotifyAccessCredential(
        String accessToken,
        Long connectionId,
        long authorizationGeneration,
        long spotifyAuthorizationVersion,
        String scopes
) {
    @Override
    public String toString() {
        return "SpotifyAccessCredential[hidden token, connectionId=" + connectionId
                + ", generation=" + authorizationGeneration + "]";
    }
}
