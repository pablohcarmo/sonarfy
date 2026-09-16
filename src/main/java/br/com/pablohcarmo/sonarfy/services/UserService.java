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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class UserService implements UserDetailsService {

    @Value("${app.base-url}")
    private String baseUrl;

    private final UserRepository userRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    public UserService (UserRepository userRepository, PermissionRepository permissionRepository, PasswordEncoder passwordEncoder, EmailService emailService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.permissionRepository = permissionRepository;
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

        // Persiste usuário para que o token aponte para uma entidade gerenciada
        user = userRepository.save(user);
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

        // Envia o e-mail de confirmação
        try {
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

            return "E-mail confirmado com sucesso! Sua conta está ativada.";
        } catch (IllegalArgumentException e) {
            return "Formato do token inválido";
        }
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
        user.setCity(updateUserDto.getCountry());

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

    public String sendPasswordResetEmail() {
        return null;
    }

    @Transactional
    public String sendPasswordChangeRequest(String email) {
        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found!"));

    String subject = "Redefinição de senha - Sonarfy";
    String body = "Olá, " + user.getName() + " " + user.getSurname() +
            "\n\nRecebemos uma solicitação para redefinir sua senha. " +
            "\nPara redefinir sua senha, clique no link abaixo:\n" +
            "Este link é válido por 15 minutos. Se você não solicitou essa alteração, ignore este e-mail." +
            "\n\nAtenciosamente,\nEquipe Sonarfy";

        try {
            emailService.sendEmail(user.getEmail(), subject, body);
            return "Reset password email sent successfully! Please check your inbox.";
        } catch (Exception e) {
            throw new RuntimeException("Failed to send reset password email: " + e.getMessage());
        }
    }

    @Transactional
    public String sendEmailChangeRequest() {
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