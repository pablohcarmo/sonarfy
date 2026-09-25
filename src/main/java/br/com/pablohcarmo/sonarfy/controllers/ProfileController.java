package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.UpdateProfileDto;
import br.com.pablohcarmo.sonarfy.dto.UpdateUserDto;
import br.com.pablohcarmo.sonarfy.dto.UserDto;
import br.com.pablohcarmo.sonarfy.services.UserService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final UserService userService;

    public ProfileController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ResponseEntity<UserDto>getCurrentUser(Principal principal) {
        // Busca o usuário logado e devolve o DTO correspondente
        return ResponseEntity.ok(userService.getUserRegister(principal.getName()));
    }

    @PutMapping("/me")
    public ResponseEntity<UserDto>updateProfile(@RequestBody @Valid UpdateProfileDto updateProfileDto, Principal principal) {
        // Envia os dados limpos para a camada de serviço, que vai atualizar o usuário logado e devolver o DTO correspondente
        return ResponseEntity.ok(userService.updateProfile(principal.getName(), updateProfileDto));
    }
}
