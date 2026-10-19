package br.com.pablohcarmo.sonarfy.controllers;

import br.com.pablohcarmo.sonarfy.entities.User;
import br.com.pablohcarmo.sonarfy.repositories.PermissionRepository;
import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.services.AuthEmailService;
import br.com.pablohcarmo.sonarfy.services.EmailService;
import br.com.pablohcarmo.sonarfy.services.EmailCooldownService;
import br.com.pablohcarmo.sonarfy.services.EmailTemplateService;
import org.springframework.context.ApplicationEventPublisher;
import br.com.pablohcarmo.sonarfy.services.JwtService;
import br.com.pablohcarmo.sonarfy.services.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;


import java.util.Optional;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integra o controller aos serviços reais, incluindo geração de JWT e tratamento
 * de SMTP. Persistência e transporte são simulados: não valida banco, transações
 * ou configuração de segurança HTTP, que não fazem parte desta tarefa.
 */
class ForgotPasswordFlowTest {

    private static final String GENERIC_RESPONSE =
            "If an eligible account exists, you may receive password reset instructions. Please check your inbox.";

    private UserRepository userRepository;
    private JavaMailSender mailSender;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    enum Scenario {
        UNKNOWN_EMAIL, UNVERIFIED_ACCOUNT, ELIGIBLE_ACCOUNT, ACTIVE_COOLDOWN, SMTP_FAILURE
    }

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        mailSender = mock(JavaMailSender.class);

        EmailService emailService = new EmailService();
        ReflectionTestUtils.setField(emailService, "javaMailSender", mailSender);
        ReflectionTestUtils.setField(emailService, "from", "noreply@example.com");

        JwtService jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "secretKey", "local-test-secret-not-for-production");

        AuthEmailService authEmailService = new AuthEmailService(
                emailService,
                mock(EmailTemplateService.class),
                userRepository,
                jwtService,
                new EmailCooldownService(userRepository, 30));
        ReflectionTestUtils.setField(authEmailService, "frontendUrl", "http://localhost:3000");


        UserService userService = new UserService(userRepository, mock(PermissionRepository.class),
                mock(PasswordEncoder.class), jwtService, authEmailService, mock(ApplicationEventPublisher.class));
        AuthController controller = new AuthController(userService, jwtService, authEmailService);

        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator).build();
    }

    @AfterEach
    void tearDown() {
        validator.close();
    }

    @ParameterizedTest(name = "Resposta pública uniforme e envio correto: {0}")
    @EnumSource(Scenario.class)
    void shouldKeepPublicResponseUniformWithoutSkippingEligibleDelivery(Scenario scenario) throws Exception {
        User user = new User();
        user.setId(10L);
        user.setName("Test");
        user.setSurname("User");
        user.setEmail("user@example.com");
        user.setVerified(scenario != Scenario.UNVERIFIED_ACCOUNT);

        when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(
                scenario == Scenario.UNKNOWN_EMAIL ? Optional.empty() : Optional.of(user));
        if (scenario == Scenario.ACTIVE_COOLDOWN) {
            when(userRepository.reserveEmailSend(any(), any(), anyBoolean(), any(), any())).thenReturn(0);
        }
        if (scenario == Scenario.ELIGIBLE_ACCOUNT || scenario == Scenario.SMTP_FAILURE) {
            when(userRepository.reserveEmailSend(any(), any(), anyBoolean(), any(), any())).thenReturn(1);
        }
        if (scenario == Scenario.SMTP_FAILURE) {
            doThrow(new MailSendException("SMTP offline"))
                    .when(mailSender).send(any(SimpleMailMessage.class));
        }

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(GENERIC_RESPONSE))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(header().doesNotExist("Retry-After"));

        verify(userRepository).findByEmailIgnoreCase(user.getEmail());
        boolean shouldAttemptDelivery = scenario == Scenario.ELIGIBLE_ACCOUNT || scenario == Scenario.SMTP_FAILURE;
        if (shouldAttemptDelivery) {
            // Um endpoint que sempre retorna 200, mas nunca envia, não pode passar.
            verify(mailSender).send(any(SimpleMailMessage.class));
            verify(userRepository).reserveEmailSend(eq(10L), eq(user.getEmail()), eq(true), any(), any());
            verify(userRepository, never()).save(any());
        } else {
            verifyNoInteractions(mailSender);
            verify(userRepository, never()).save(any());
        }
    }
}
