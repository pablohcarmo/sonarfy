package br.com.pablohcarmo.sonarfy.config;

import br.com.pablohcarmo.sonarfy.repositories.UserRepository;
import br.com.pablohcarmo.sonarfy.services.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AvatarConfigTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(AvatarConfig.class)
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(ProfileService.class, () -> mock(ProfileService.class))
            .withBean(jakarta.persistence.EntityManager.class, () -> mock(jakarta.persistence.EntityManager.class))
            .withBean(org.springframework.transaction.PlatformTransactionManager.class,
                    () -> mock(org.springframework.transaction.PlatformTransactionManager.class));

    // Desabilitado dispensa Azure; habilitado configura os limites do servlet.
    @Test
    void configuresStorageOnlyWhenEnabled() {
        context.run(app -> assertFalse(app.containsBean("avatarStorage")));
        context.withPropertyValues("app.avatar.enabled=true", "app.avatar.endpoint=https://account.blob.core.windows.net")
                .run(app -> {
                    assertNull(app.getStartupFailure());
                    assertEquals(5242880, app.getBean(jakarta.servlet.MultipartConfigElement.class).getMaxFileSize());
                    assertEquals(512, app.getBean(AvatarProperties.class).size());
                    assertTrue(app.getBean(AzureAvatarStorage.class).url("avatar.png")
                            .startsWith("https://account.blob.core.windows.net/images/"));
                });
        context.withPropertyValues("app.wallpaper.enabled=true", "app.avatar.endpoint=https://account.blob.core.windows.net")
                .run(app -> {
                    assertNull(app.getStartupFailure());
                    assertEquals(1600, app.getBean(WallpaperProperties.class).maxWidth());
                    assertTrue(app.containsBean("avatarStorage"));
                });
    }

    // Configuração inválida impede a inicialização do recurso.
    @Test
    void requiresValidEndpointAndLimits() {
        context.withPropertyValues("app.avatar.enabled=true", "app.avatar.endpoint=http://invalid", "app.avatar.max-bytes=0")
                .run(app -> assertNotNull(app.getStartupFailure()));
    }
}
