package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.dto.NewUserDto;
import br.com.pablohcarmo.sonarfy.dto.UpdateProfileDto;
import br.com.pablohcarmo.sonarfy.dto.UpdateUserDto;
import br.com.pablohcarmo.sonarfy.dto.UserDto;
import br.com.pablohcarmo.sonarfy.entities.Permission;
import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.PermissionRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class UserService implements UserDetailsService {

    @Value("${app.base-url}")
    private String baseUrl;

    private final UserRepository userRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final JwtService jwtService;
    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    public UserService (UserRepository userRepository, PermissionRepository permissionRepository, PasswordEncoder passwordEncoder, EmailService emailService, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.permissionRepository = permissionRepository;
        this.jwtService = jwtService;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // Caso o usuário acesse com o handle, remove o "@" do início
        String cleanUsername = username.startsWith("@") ? username.substring(1) : username;

        return userRepository.findByEmailIgnoreCaseOrHandleIgnoreCase(cleanUsername, cleanUsername)
                .orElseThrow(() -> new UsernameNotFoundException("User not found!"));
    }

    @Transactional
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
        user.setCity(newUserDto.getCity());
        user.setCountry(newUserDto.getCountry());
        user.setBirthDate(newUserDto.getBirthDate());

        // Define a permissão do usuário como "ROLE_USER" por padrão
        Permission defaultPermission = new Permission();
        defaultPermission = permissionRepository.findByName("ROLE_USER")
                .orElseThrow(() -> new RuntimeException("Default permission not found!"));
        user.setPermissionId(defaultPermission);

        user = userRepository.save(user);

        String jwtToken = jwtService.generateEmailConfirmationToken(user.getId().toString());
        sendActivationEmail(user, jwtToken);
    }

    @Transactional
    public String verifyToken(String jwtToken) {
        if(jwtToken == null ||jwtToken.isBlank()) {
            return "Invalid token provided for verification.";
        }

        // Valida o token JWT extraindo o ID do usuário
        String userIdFromJwt = jwtService.validateTokenAndGetEmail(jwtToken);

        // Verifica se o ID do usuário foi extraído corretamente do token
        if(userIdFromJwt == null || userIdFromJwt.isBlank()) {
            return "Invalid or expired token. Please, request a new confirmation email.";
        }

        long userId;
        try {
            userId = Long.parseLong(userIdFromJwt);
        } catch (NumberFormatException e) {
            return "Invalid token format. Please, request a new confirmation email.";
        }

        // Verifica se o usuário existe pelo ID extraído do token
        Optional<User> optionalUser = userRepository.findById(userId);
        if(optionalUser.isEmpty()) {
            return "User not found or account was deleted.";
        }

        // Caso encontre o usuário, instancia o objeto User e busca se ele já foi verificado
        User user = optionalUser.get();

        // Verifica se o usuário já foi verificado
        if(user.isVerified()) {
            return "User already verified. You can log in.";
        }

        // Ativa o usuário e salva no banco de dados
        user.setVerified(true);
        userRepository.save(user);
        sendWelcomeEmail(user);
        return "E-mail verified successfully! You can now log in.";
    }

    public UserDto getUserRegister(String email) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found!"));

        return new UserDto(user.getId(), user.getName(), user.getSurname(), user.getHandle(), user.getEmail(),
                user.isActive(), user.getCity(), user.getCountry(), user.getAvatar(), user.getWallpaper(),
                user.getBiography(), user.getBirthDate(), user.getCreationDate().toLocalDateTime(),
                user.getLastLoginDate().toLocalDateTime(), user.getLastUpdateDate().toLocalDateTime());
    }

    public UserDto updateRegister(String email, UpdateUserDto updateUserDto) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found!"));

        // Atualiza os campos do usuário com os dados do DTO
        user.setName( updateUserDto.getName());
        user.setSurname(updateUserDto.getSurname());
        user.setBirthDate(updateUserDto.getBirthDate());
        user.setCity(updateUserDto.getCity());
        user.setCountry(updateUserDto.getCountry());

        userRepository.save(user);
        return getUserRegister(email);
    }

    public UserDto updateProfile(String email, UpdateProfileDto updateProfileDto) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found!"));

        user.setAvatar(updateProfileDto.getAvatar());
        user.setWallpaper(updateProfileDto.getWallpaper());
        user.setBiography(updateProfileDto.getBiography());

        userRepository.save(user);
        return getUserRegister(email);
    }

    public void sendWelcomeEmail(User user) {
        String subject = "Bem-vindo ao Sonarfy!";
        String body = "Olá " + user.getName() + " " + user.getSurname() +
                ".\n\nSua conta foi ativada com sucesso!\nEstamos felizes em tê-lo conosco." +
                "\nVocê já pode fazer login no Sonarfy e começar a avaliar seus álbuns favoritos!\n\n" +
                "Atenciosamente,\nEquipe Sonarfy";
        try {
            this.emailService.sendEmail(user.getEmail(), subject, body);
        } catch (Exception e) {
            logger.error("Failed to send welcome email: ", e);
        }
    }

    public void sendActivationEmail(User user, String jwtToken) {
        String activationLink = baseUrl + "/verify?token=" + jwtToken;

        String subject = "Ative sua conta no Sonarfy!";
        String body = "Olá " + user.getName() + " " + user.getSurname() +
                ".\n\nPara concluir o seu cadastro e ativar a sua conta, por favor clique no link abaixo:\n" +
                activationLink + "\n\n" +
                "\nSe o link não funcionar, copie e cole no seu navegador.\n\n" +
                "Atenciosamente,\nEquipe Sonarfy";
        try {
            this.emailService.sendEmail(user.getEmail(), subject, body);
        } catch (Exception e) {
            // Exceção para dar Rollback no cadastro se o link falhar
            throw new RuntimeException("Failed to send activation token email: " + e.getMessage());
        }
    }

    @Transactional
    public String resendActivationEmail(String email){
        // Validação de input
        if(email == null || email.isBlank()) {
            return "Invalid e-mail provided for resending confirmation.";
        }

        // Busca o usuário no banco de dados
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found!"));

        // Verifica se o usuário já foi verificado
        if(user.isVerified()) {
            return "User already verified. You can log in.";
        }

        // Gera um novo token JWT para o e-mail do usuário
        String jwtToken = jwtService.generateEmailConfirmationToken(user.getId().toString());

        // Envia o e-mail de confirmação
        try {
            sendActivationEmail(user, jwtToken);
            return "Confirmation email resent successfully! Please check your inbox.";
        } catch (Exception e) {
            // O Rollback cancela qualquer transação pendente se o e-mail falhar
            throw new RuntimeException("Failed to resend email confirmation: " + e.getMessage());
        }
    }

    @Transactional
    public String sendPasswordChangeRequest(String email) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found!"));

        // TODO: Gerar o link de redefinição de senha com token JWT
        String mockResetLink = baseUrl + "/reset-password?token=simulacao-temporaria";

        String subject = "Redefinição de senha - Sonarfy";
        String body = "Olá, " + user.getName() + " " + user.getSurname() +
                "\n\nRecebemos uma solicitação para redefinir sua senha. " +
                "\nPara redefinir sua senha, clique no link abaixo:\n" +
                mockResetLink +
                "\n\nEste link é válido por 15 minutos. Se você não solicitou essa alteração, ignore este e-mail." +
                "\n\nAtenciosamente,\nEquipe Sonarfy";

        try {
            emailService.sendEmail(user.getEmail(), subject, body);
            return "Reset password email sent successfully! Please check your inbox.";
        } catch (Exception e) {
            throw new RuntimeException("Failed to send reset password email: " + e.getMessage());
        }
    }
}