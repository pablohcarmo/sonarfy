package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;


@Service
public class AuthEmailService {

    @Value("${app.frontend-url}")
    private String frontendUrl;

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final JwtService jwtService;
    private final EmailCooldownService emailCooldownService;
    private static final Logger logger = LoggerFactory.getLogger(AuthEmailService.class);

    public AuthEmailService(EmailService emailService, UserRepository userRepository, JwtService jwtService, EmailCooldownService emailCooldownService) {
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.jwtService = jwtService;
        this.emailCooldownService = emailCooldownService;
    }

    public void sendActivationEmail(User user, String jwtToken) {
        // Se o usuário já estiver verificado, não envia o e-mail de ativação
        if (user.isVerified()) {
            return;
        }
        emailCooldownService.reserveOrThrow(user.getId(), user.getEmail(), false);

        String activationLink = frontendUrl + "/verify.html?token=" + jwtToken;

        String subject = "Ative sua conta no Sonarfy!";
        String body = "Olá " + user.getName() + " " + user.getSurname() +
                ".\n\nPara concluir o seu cadastro e ativar a sua conta, por favor clique no link abaixo:\n" +
                activationLink + "\n\n" +
                "\nSe o link não funcionar, copie e cole no seu navegador.\n\n" +
                "Atenciosamente,\nEquipe Sonarfy";

        // Dispara o e-mail de ativação
        this.emailService.sendEmail(user.getEmail(), subject, body);
    }

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
        String token = jwtService.generateEmailConfirmationToken(user.getId().toString());
        sendActivationEmail(user, token);
        return "Confirmation email request processed. Please check your inbox.";
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

    public String sendPasswordResetEmail(User user, String resetToken) {
        // Impede que usuários não confirmados solicitem redefinição de senha
        if (!user.isVerified()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "User is not verified. Please verify your account before requesting a password reset.");
        }

        emailCooldownService.reserveOrThrow(user.getId(), user.getEmail(), true);

        // Gera o link de redefinição de senha com o token JWT
        String resetLink = frontendUrl + "/reset-password.html?token=" + resetToken;

        String subject = "Redefinição de senha - Sonarfy";
        String body = "Olá, " + user.getName() + " " + user.getSurname() +
                "\n\nRecebemos uma solicitação para redefinir sua senha. " +
                "\nPara redefinir sua senha, clique no link abaixo:\n" + resetLink +
                "\n\nEste link é válido por 15 minutos. Se você não solicitou essa alteração, ignore este e-mail." +
                "\n\nAtenciosamente,\nEquipe Sonarfy";

        // Deixa EmailDeliveryException subir até o chamador com seu tipo preservado
        // O fluxo público decide como responder, e o autenticado pode tratar diferente.
        emailService.sendEmail(user.getEmail(), subject, body);
        return "Reset password email sent successfully! Please check your inbox.";
    }

    public String sendPasswordChangeEmail(User user, String changeToken) {
        if (!user.isVerified()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "User is not verified. Please verify your account before requesting a password change.");
        }

        emailCooldownService.reserveOrThrow(user.getId(), user.getEmail(), true);

        String subject = "Autorização para troca de senha - Sonarfy";
        String body = "Olá, " + user.getName() + " " + user.getSurname() +
                "\n\nRecebemos uma solicitação de troca de senha estando autenticado." +
                "\nUse o token abaixo na tela de configurações da sua conta:" +
                "\n\n" + changeToken +
                "\n\nEste token é válido por 15 minutos e não pode ser usado para redefinir uma senha esquecida." +
                " Se você não solicitou essa alteração, ignore este e-mail." +
                "\n\nAtenciosamente,\nEquipe Sonarfy";

        emailService.sendEmail(user.getEmail(), subject, body);
        return "Password change authorization email sent successfully. Please check your inbox.";
    }

}
