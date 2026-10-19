package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.exceptions.EmailDeliveryException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Service
public class EmailService {
    @Autowired
    private JavaMailSender javaMailSender;

    @Value("${spring.mail.username}")
    private String from;

    public String sendEmail(String to, String subject, String body){
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);

        try {
            javaMailSender.send(message);
        } catch (MailException e) {
            // Traduz a falha conhecida para o tipo usado pela aplicação. Outros erros continuam sendo propagados.
            throw new EmailDeliveryException("Failed to deliver email.", e);
        }
        return "Email delivered to " + to;
    }

    public String sendHtmlEmail(String to, String subject, EmailContent content) {
        try {
            MimeMessage message = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message,
                    MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name()
            );
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(content.textBody(), content.htmlBody());

            for (var resource : content.inlineResources().entrySet()) {
                helper.addInline(
                        resource.getKey(),
                        new ClassPathResource(resource.getValue()),
                        "image/png"
                );
            }

            javaMailSender.send(message);
            return "Email delivered to " + to;
        } catch (MailException | MessagingException e) {
            throw new EmailDeliveryException("Failed to deliver email.", e);
        }
    }
}
