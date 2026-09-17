package br.com.pablohcarmo.sonarfy.services;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class JwtService {

    @Value("${api.security.token.secret}")
    private String secretKey;

    public String generateEmailConfirmationToken(String email) {
        if(email == null || email.isEmpty()) {
            throw new IllegalArgumentException("Cannot generate token for a null or blank email.");
        }
        Algorithm algorithm = Algorithm.HMAC256(secretKey);

        return JWT.create()
                .withIssuer("sonarfy-api")
                .withSubject(email)
                .withClaim("purpose", "email_confirmation")
                .withExpiresAt(Instant.now().plusSeconds(900)) // 15 minutes
                .sign(algorithm);
    }

    public String validateTokenAndGetEmail(String token) {
        try {
            Algorithm algorithm = Algorithm.HMAC256(secretKey);

            // Verifica se a assinatura do token é autêntica,
            // que o emissor é sonarfy-api e se o token não expirou
            DecodedJWT decodedJWT = JWT.require(algorithm)
                    .withIssuer("sonarfy-api")
                    .withClaim("purpose", "email_confirmation")
                    .build()
                    .verify(token);

            // Se as verificações passarem, retorna o email do usuário
            return decodedJWT.getSubject();
        } catch (JWTVerificationException e) {
            // Se a chave for falsa ou o token estiver expirado, retorna null
            // Outros tipos de erros da aplicação não devem ser tratados aqui, apenas erros de verificação do token
            return null;
        }
    }
}
