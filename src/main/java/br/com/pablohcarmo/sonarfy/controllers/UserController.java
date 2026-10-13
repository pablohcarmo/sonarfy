package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.ChangePasswordDto;
import br.com.pablohcarmo.sonarfy.dto.UpdateUserDto;
import br.com.pablohcarmo.sonarfy.dto.UserDto;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.services.AuthEmailService;
import br.com.pablohcarmo.sonarfy.services.JwtService;
import br.com.pablohcarmo.sonarfy.services.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final AuthEmailService authEmailService;
    private final UserService userService;
    private final JwtService jwtService;
    private final UserRepository userRepository;

    public UserController(UserService userService, AuthEmailService authEmailService, JwtService jwtService, UserRepository userRepository) {
        this.userService = userService;
        this.authEmailService = authEmailService;
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto>getCurrentUser(Principal principal) {
        // Busca o usuário logado e devolve o DTO correspondente
        return ResponseEntity.ok(userService.getUserRegister(principal.getName()));
    }

    @PutMapping("/me")
    public ResponseEntity<UserDto>updateRegister(@RequestBody @Valid UpdateUserDto updateUserDto, Principal principal) {
        // Envia os dados limpos para a camada de serviço, que vai atualizar o usuário logado e devolver o DTO correspondente
        return ResponseEntity.ok(userService.updateRegister(principal.getName(), updateUserDto));
    }

    /*@PostMapping("/me/email-change")
    public ResponseEntity<String> requestEmailChange(Principal principal) {
        String userEmail = principal.getName();
        String result = userService.sendEmailChangeRequest(userEmail);
        return ResponseEntity.ok(result);
    }*/

    @PostMapping("/me/password-change-request")
    public ResponseEntity<String> requestPasswordChange(Principal principal) {
        User user = userRepository.findByEmailIgnoreCase(principal.getName())
                .orElseThrow(() -> new UsernameNotFoundException("User not found!"));

        String token = jwtService.generatePasswordResetToken(user.getId().toString());
        String result  = authEmailService.sendPasswordResetEmail(user, token);
        return ResponseEntity.ok(result);

    }

    @PutMapping("/me/password")
    public ResponseEntity<String> changePassword(@Valid @RequestBody ChangePasswordDto dto, Principal principal) {
        userService.changePassword(principal.getName(), dto);
        return ResponseEntity.ok("Password updated successfully.");
    }
}
