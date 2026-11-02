package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.LoginDto;
import br.com.pablohcarmo.sonarfy.dto.LoginResponseDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String dummyPasswordHash;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.dummyPasswordHash =
                passwordEncoder.encode("dummy-login-password");
    }

    public LoginResponseDto login(LoginDto dto) {
        String login = dto.getLogin().trim();
        String handle = login.startsWith("@") ? login.substring(1) : login;

        User user = userRepository
                .findByEmailIgnoreCaseOrHandleIgnoreCase(login, handle)
                .orElse(null);

        String passwordHash = user != null
                ? user.getPassword()
                : dummyPasswordHash;

        boolean validPassword =
                passwordEncoder.matches(dto.getPassword(), passwordHash);

        if (user == null || !validPassword || !user.isEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Invalid login or password."
            );
        }

        String accessToken =
                jwtService.generateAccessToken(user.getId().toString());

        return new LoginResponseDto(
                accessToken,
                "Bearer",
                JwtService.ACCESS_TOKEN_TTL_SECONDS
        );
    }
}
