package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.exceptions.EmailDeliveryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private JavaMailSender javaMailSender;

    @InjectMocks
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(emailService, "from", "noreply@example.com");
    }

    @Test
    @DisplayName("Entrega ao transporte a mensagem com remetente, destinatário, assunto e corpo")
    void shouldSendConfiguredMessage() {
        assertDoesNotThrow(() -> emailService.sendEmail("user@example.com", "Subject", "Body"));

        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(message.capture());
        assertEquals("noreply@example.com", message.getValue().getFrom());
        assertArrayEquals(new String[]{"user@example.com"}, message.getValue().getTo());
        assertEquals("Subject", message.getValue().getSubject());
        assertEquals("Body", message.getValue().getText());
    }

    @Test
    @DisplayName("Converte falha de SMTP em EmailDeliveryException preservando a causa")
    void shouldWrapMailFailureAndPreserveCause() {
        MailSendException failure = new MailSendException("SMTP offline");
        doThrow(failure).when(javaMailSender).send(any(SimpleMailMessage.class));

        EmailDeliveryException actual = assertThrows(EmailDeliveryException.class,
                () -> emailService.sendEmail("user@example.com", "Subject", "Body"));

        assertSame(failure, actual.getCause());
        verify(javaMailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("Não classifica erro inesperado como falha de entrega")
    void shouldPropagateUnexpectedFailureUnchanged() {
        IllegalStateException failure = new IllegalStateException("Unexpected failure");
        doThrow(failure).when(javaMailSender).send(any(SimpleMailMessage.class));

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> emailService.sendEmail("user@example.com", "Subject", "Body")));
    }
}
