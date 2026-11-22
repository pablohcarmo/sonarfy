package br.com.pablohcarmo.sonarfy.exceptions;

// Indica que a conexão Spotify mudou ou perdeu validade antes da persistência das escutas.
public class StaleSpotifyAuthorizationException extends RuntimeException {

    public StaleSpotifyAuthorizationException() {
        super("Spotify authorization is no longer valid for persisting listens.");
    }
}
