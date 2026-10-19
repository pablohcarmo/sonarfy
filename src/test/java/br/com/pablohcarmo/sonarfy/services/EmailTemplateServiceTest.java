package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.entities.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmailTemplateServiceTest {

    private EmailTemplateService templates;
    private User user;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        templates = new EmailTemplateService(engine);

        user = new User();
        user.setName("Pablo");
        user.setSurname("Carmo");
    }

    @Test
    void rendersActivationWithDynamicLinksAndAccessibleTextAlternative() {
        EmailContent content = templates.activation(user, "https://sonarfy.example/verify?token=abc");

        assertTrue(content.htmlBody().contains("Confirme seu e-mail"));
        assertTrue(content.htmlBody().contains("Olá, Pablo Carmo"));
        assertEquals(2, occurrences(content.htmlBody(), "https://sonarfy.example/verify?token=abc"));
        assertTrue(content.htmlBody().contains(">aqui</a>"));
        assertFalse(content.htmlBody().contains("[aqui]"));
        assertTrue(content.textBody().contains("https://sonarfy.example/verify?token=abc"));
        assertEquals("email-assets/mail.png", content.inlineResources().get("statusIcon"));
    }

    @Test
    void rendersResendWithFinalValidityCopy() {
        EmailContent content = templates.activationResend(user, "https://sonarfy.example/verify?token=new");

        assertTrue(content.htmlBody().contains("Seu novo link de ativação"));
        assertTrue(content.htmlBody().contains(
                "Se ele estiver inválido ou expirado, solicite um novo na página de cadastro do Sonarfy."));
        assertTrue(content.textBody().contains("Use o link mais recente"));
        assertEquals("email-assets/mail-check.png", content.inlineResources().get("statusIcon"));
    }

    @Test
    void rendersWelcomeWithAccountLinkAndAllFigmaIcons() {
        EmailContent content = templates.welcome(user, "https://sonarfy.example/login");

        assertTrue(content.htmlBody().contains("Cadastro confirmado!"));
        assertTrue(content.htmlBody().contains("https://sonarfy.example/login"));
        assertTrue(content.htmlBody().contains("Avalie seus álbuns favoritos"));
        assertTrue(content.htmlBody().contains("Descubra novos artistas"));
        assertTrue(content.htmlBody().contains("Encontre sua comunidade"));
        assertEquals(4, content.inlineResources().size());
    }

    private static int occurrences(String text, String expected) {
        return (text.length() - text.replace(expected, "").length()) / expected.length();
    }
}
