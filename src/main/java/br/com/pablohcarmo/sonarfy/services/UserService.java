package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.NewUserDto;
import br.com.pablohcarmo.sonarfy.entities.Permission;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.entities.UserTokenConfirmation;
import br.com.pablohcarmo.sonarfy.repositories.PermissionRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserTokenConfirmationRepository;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserService implements UserDetailsService {

    @Value("${app.base-url}")
    private String baseUrl;

    private final UserRepository userRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final UserTokenConfirmationRepository userTokenConfirmationRepository;

    public UserService (UserRepository userRepository, PermissionRepository permissionRepository, PasswordEncoder passwordEncoder, EmailService emailService, UserTokenConfirmationRepository userTokenConfirmationRepository) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.permissionRepository = permissionRepository;
        this.userTokenConfirmationRepository = userTokenConfirmationRepository;
    }

    // TODO - Login do usuário, podendo ser feito tanto pelo email quanto pelo handle
    // Se quiser deixar explícito que é um processo de login,
    // criar uma classe separada chamada AuthService com um método login(), mas o loadUserByUsername precisará continuar existindo intacto aqui no UserService para alimentar o sistema.
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Caso o usuário acesse com o handle, remove o "@" do início
        String cleanUsername = username.startsWith("@") ? username.substring(1) : username;

        return userRepository.findByEmailIgnoreCaseOrHandleIgnoreCase(cleanUsername, cleanUsername)
                .orElseThrow(() -> new UsernameNotFoundException("User not found!"));
    }

    public void newUser(NewUserDto newUserDto) {
        // Limpar o handle, caso o usuário tenha digitado com "@"
        String cleanHandle = newUserDto.getHandle().startsWith("@")
                ? newUserDto.getHandle().substring(1)
                : newUserDto.getHandle();

        // Verificar se já existe um usuário com o mesmo email ou handle
        boolean userExists = userRepository.findByEmailIgnoreCaseOrHandleIgnoreCase(
                newUserDto.getEmail(), cleanHandle
        ).isPresent();

        if (userExists) {
            throw new RuntimeException("User with this email or handle already exists!");
        }

        User user = new User();
        user.setName(newUserDto.getName());
        user.setSurname(newUserDto.getSurname());
        user.setHandle((cleanHandle));
        user.setEmail(newUserDto.getEmail());
        user.setPassword(passwordEncoder.encode(newUserDto.getPassword()));
        user.setBirthDate(newUserDto.getBirthDate());

        // Define a permissão do usuário como "ROLE_USER" por padrão
        Permission defaultPermission = new Permission();
        defaultPermission = permissionRepository.findByName("ROLE_USER")
                .orElseThrow(() -> new RuntimeException("Default permission not found!"));
        user.setPermissionId(defaultPermission);

        // Persiste usuário para que o token aponte para uma entidade gerenciada
        user = userRepository.save(user);
        
        UserTokenConfirmation verificationToken = new UserTokenConfirmation();
        verificationToken.setUser(user);
        verificationToken.setUuid(UUID.randomUUID());

        // Token expira em 15 minutos
        verificationToken.setExpiresAt(Instant.now().plusSeconds(900));
        userTokenConfirmationRepository.save(verificationToken);

        // Enviar email de boas-vindas para o usuário
        sendWelcomeEmail(user, verificationToken.getUuid());
    }

    public void sendWelcomeEmail(User user, UUID uuidToken) {
        String activationLink = baseUrl + "/verify?token=" + uuidToken.toString();
        String subject = "Bem-vindo ao Sonarfy!";
        String body = "Olá " + user.getName() + "\n\nSua conta foi criada com sucesso! " +
                "Obrigado por se registrar no Sonarfy! Estamos felizes em tê-lo conosco." +
                "\n\nPara ativar sua conta, por favor clique no link abaixo:\n" +
                "\nSe o link não funcionar, copie e cole o seguinte URL no seu navegador:\n" +
                activationLink + "\n\n" +
                "Atenciosamente,\nEquipe Sonarfy";
        try {
            this.emailService.sendEmail(user.getEmail(), subject, body);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send welcome email: " + e.getMessage());
        }
    }

    @Transactional
    public String resendEmailConfirmation(String email){
        // Validação de input
        if(email == null || email.isBlank()) {
            return "Informe um e-mail válido para reenviar a confirmação.";
        }

        // Busca o usuário no banco de dados
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("Usuário não encontrado!"));

        // Verifica se o usuário já foi verificado
        if(user.isVerified()) {
            return "O seu e-mail já foi confirmado. Você já pode fazer login.";
        }

        // Cria um novo token de confirmação
        UserTokenConfirmation newToken = new UserTokenConfirmation();
        newToken.setUser(user);

        // UUID e setAt gerado automaticamente pelo @PrePersist da entidade UserTokenConfirmation
        newToken.setExpiresAt(Instant.now().plusSeconds(900)); // 15 minutos
        userTokenConfirmationRepository.save(newToken);

        // Envia o e-mail de confirmação
        try {
            sendWelcomeEmail(user, newToken.getUuid());
            return "E-mail de confirmação reenviado com sucesso! Verifique sua caixa de entrada.";
        } catch (Exception e) {
            // Se houver algum erro ao enviar o e-mail, lança uma exceção para cancelar o .save() do UUID,
            // ou seja, não salva o newToken no banco de dados
            throw new RuntimeException("Falha ao enviar o e-mail de confirmação: " + e.getMessage());
        }
    }

    @Transactional
    public String verifyToken(String uuidConverted) {
        if(uuidConverted == null || uuidConverted.isBlank()) {
            return "Token não fornecido.";
        }
        try {
            // Conversão e busca do UUID no banco de dados
            UUID uuid = UUID.fromString(uuidConverted);
            Optional<UserTokenConfirmation> optToken = userTokenConfirmationRepository.findByUuid(uuid);

            if (optToken.isEmpty()) {
                return "Token inválido";
            }

            UserTokenConfirmation token = optToken.get();

            // Verifica se o token já foi utilizado
            if (token.getUsed()) {
                return "Este link já foi utilizado. Sua conta já está ativa.";
            }

            // Verifica se o token expirou
            if (Instant.now().isAfter(token.getExpiresAt())) {
                return "Token expirado. Por favor, solicite um novo e-mail de confirmação.";
            }

            User user = token.getUser();
            user.setVerified(true);
            userRepository.save(user);

            token.setUsed(true);
            userTokenConfirmationRepository.save(token);

            return "E-mail confirmado com sucesso! Sua conta está ativada.";
        } catch (IllegalArgumentException e) {
            return "Formato do token inválido";
        }
    }

    public void updateProfile() {
    }

    public String sendPasswordResetEmail() {
        return null;
    }

    public String resetPassword() {
        return null;
    }

    public String updatePassword() {
        return null;
    }

    public String validatePasswordResetToken() {
        return null;
    }

    public String deleteAccount() {
        return null;
    }
    public String deactivateAccount() {
        return null;
    }

    // Verificar, pois o usuário pode reativar a conta logando novamente

    public String reactivateAccount() {
        return null;
    }

    public String changeAvatar() {
        return null;
    }

    public String changeBiography() {
        return null;
    }

    public String changeWallpaper() {
        return null;
    }

    public String findUserByHandle() {
        return null;
    }

    // TODO - verificar a necessidade de uma classe SocialService para lidar com as redes sociais,
    //  ou se isso deve ser feito aqui mesmo no UserService

    public String viewProfile() {
        return null;
    }

    public String viewFollowers() {
        return null;
    }

    public String viewFollowing() {
        return null;
    }
}