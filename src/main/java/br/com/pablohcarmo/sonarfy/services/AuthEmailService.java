package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import jakarta.transaction.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;

@Service
public class AuthEmailService {

    @Value("${app.base-url}")
    private String baseUrl;

    @Value("${app.frontend-url}")
    private String frontendUrl;

    @Value("${app.email.cooldown-seconds:30}")
    private long emailCooldownSeconds;

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final JwtService jwtService;
    private static final Logger logger = LoggerFactory.getLogger(AuthEmailService.class);

    public AuthEmailService(EmailService emailService, UserRepository userRepository, JwtService jwtService) {
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.jwtService = jwtService;
    }

    public void sendActivationEmail(User user, String jwtToken) {
        String activationLink = frontendUrl + "/verify.html?token=" + jwtToken;

        String subject = "Ative sua conta no Sonarfy!";
        String body = "Olá " + user.getName() + " " + user.getSurname() +
                ".\n\nPara concluir o seu cadastro e ativar a sua conta, por favor clique no link abaixo:\n" +
                activationLink + "\n\n" +
                "\nSe o link não funcionar, copie e cole no seu navegador.\n\n" +
                "Atenciosamente,\nEquipe Sonarfy";

        // Checa o cooldown antes de enviar o e-mail
        checkEmailCooldown(user);

        try {
            // Dispara o e-mail de ativação
            this.emailService.sendEmail(user.getEmail(), subject, body);

            // Atualiza a timestamp no banco de dados
            user.setLastEmailSentAt(OffsetDateTime.now());
            userRepository.save(user);
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
            user.setLastEmailSentAt(OffsetDateTime.now());

            return "Confirmation email resent successfully! Please check your inbox.";
        } catch (ResponseStatusException e) {
            throw e; // ResponseStatusException é lançado para o cliente, não precisa ser encapsulado
        } catch (Exception e) {
            // O Rollback cancela qualquer transação pendente se o e-mail falhar
            throw new RuntimeException("Failed to resend email confirmation: " + e.getMessage());
        }
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

    @Transactional
    public String sendPasswordChangeRequest(String email) {
        if(email == null || email.isBlank()) {
            return "Invalid e-mail provided for password reset.";
        }

        User user = userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found!"));

        if(!user.isVerified()) {
            return "User is not verified. Please verify your account before requesting a password reset.";
        }

        checkEmailCooldown(user);

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
            user.setLastEmailSentAt(OffsetDateTime.now());
            userRepository.save(user);

            return "Reset password email sent successfully! Please check your inbox.";
        } catch (Exception e) {
            throw new RuntimeException("Failed to send reset password email: " + e.getMessage());
        }
    }

    private void checkEmailCooldown(User user){
        if (user.getLastEmailSentAt() != null) {
            long secondsSinceLastEmail = Duration.between(user.getLastEmailSentAt(), OffsetDateTime.now()).toSeconds();
            if(secondsSinceLastEmail < emailCooldownSeconds) {
                long remainingSeconds = emailCooldownSeconds - secondsSinceLastEmail;
                throw new ResponseStatusException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "Please wait " + remainingSeconds + " seconds before requesting another email."
                );
            }
        }
    }
}