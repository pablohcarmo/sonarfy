package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.dto.LoginDto;
import br.com.pablohcarmo.sonarfy.dto.NewUserDto;
import br.com.pablohcarmo.sonarfy.services.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("api/auth")
public class AuthController {
    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> registerUser(@RequestBody NewUserDto newUserDto) {
        userService.newUser(newUserDto);
        return ResponseEntity.ok("User registered successfully");
    }
}