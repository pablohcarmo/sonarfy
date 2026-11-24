package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.config.AccountDeactivationSchedulingConfig;
import br.com.pablohcarmo.sonarfy.controllers.AccountDeactivationController;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AccountDeactivationDisabledTest {
    // Ausência e false impedem tanto endpoints quanto remetente e agendamento.
    @ParameterizedTest @ValueSource(strings = {"missing", "false"})
    void disabledByDefault(String setting) {
        try (var context = new AnnotationConfigApplicationContext()) {
            if (!setting.equals("missing")) context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("app.account-deactivation.enabled", setting)));
            context.register(AccountDeactivationController.class, AccountDeactivationDispatcher.class, AccountDeactivationSchedulingConfig.class);
            context.refresh();
            assertTrue(context.getBeansOfType(AccountDeactivationController.class).isEmpty());
            assertTrue(context.getBeansOfType(AccountDeactivationDispatcher.class).isEmpty());
            assertTrue(context.getBeansOfType(AccountDeactivationSchedulingConfig.class).isEmpty());
        }
    }
}
