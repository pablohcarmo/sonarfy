package br.com.pablohcarmo.sonarfy.services;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class JwtService {

    private final String SECRET_KEY = "your-secret-key";

    public String generateEmailConfirmationToken(String email) {
        Algorithm algorithm = Algorithm.HMAC256(SECRET_KEY);

        return JWT.create()
                .withIssuer("sonarfy-api")
                .withSubject(email)
                .withClaim("purpose", "email_confirmation")
                .withExpiresAt(Instant.now().plusSeconds(900)) // 15 minutes
                .sign(algorithm);
    }

    public String validateTokenAndGetEmail(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(SECRET_KEY);

            // Verifica se a assinatura do token é autêntica,
            // que o emissor é sonarfy-api e se o token não expirou
            DecodedJWT decodedJWT = JWT.require(algorithm)
                    .withIssuer("sonarfy-api")
                    .build()
                    .verify(token);

            // Se as verificações passarem, retorna o email do usuário
            return decodedJWT.getSubject();
        } catch (Exception e) {
            // Deve retornar nulo para que o UserService saiba que o token é inválido ou expirou
            return null;
        }
    }
}
