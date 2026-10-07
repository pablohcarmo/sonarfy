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


    private static final String ISSUER = "sonarfy-api";
    private static final String PURPOSE_EMAIL_CONFIRMATION = "email_confirmation";
    private static final String PURPOSE_PENDING_REGISTRATION = "pending_registration";

    public static final long EMAIL_CONFIRMATION_TTL_SECONDS = 900; // 15 minutes
    public static final long PENDING_REGISTRATION_TTL_SECONDS = 900; // 15 minutes

    @Value("${api.security.token.secret}")
    private String secretKey;

    public String generateEmailConfirmationToken(String userId) {
        requireNotBlank(userId, "user ID");

        return JWT.create()
                .withIssuer(ISSUER)
                .withSubject(userId)
                .withClaim("purpose",PURPOSE_EMAIL_CONFIRMATION)
                .withExpiresAt(Instant.now().plusSeconds(EMAIL_CONFIRMATION_TTL_SECONDS))
                .sign(algorithm());
    }

    public String generateRegistrationToken(String userId) {
        requireNotBlank(userId, "user ID");

        return JWT.create()
                .withIssuer(ISSUER)
                .withSubject(userId)
                .withClaim("purpose",PURPOSE_PENDING_REGISTRATION)
                .withExpiresAt(Instant.now().plusSeconds(PENDING_REGISTRATION_TTL_SECONDS))
                .sign(algorithm());
    }

    public String validateTokenAndGetEmail(String token) {
        DecodedJWT jwt = verify( token, PURPOSE_EMAIL_CONFIRMATION);
        return jwt == null ? null : jwt.getSubject();
    }

    public String validateRegistrationTokenAndGetUserId(String token) {
        DecodedJWT jwt = verify(token, PURPOSE_PENDING_REGISTRATION);
        return jwt == null ? null : jwt.getSubject();
    }

    private DecodedJWT verify(String token, String expectedPurpose) {
        if (token == null || token.isBlank()) {
            return  null;
        }
        try {
            return JWT.require(algorithm())
                    .withIssuer(ISSUER)
                    .withClaim("purpose", expectedPurpose)
                    .build()
                    .verify(token);
        } catch (JWTVerificationException e) {
            return null;
        }
    }


    private Algorithm algorithm() {
        return Algorithm.HMAC256(secretKey);
    }

    private static void requireNotBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Cannot generate token for a null or blank " + fieldName + ".");
        }
    }

}
