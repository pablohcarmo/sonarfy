package br.com.pablohcarmo.sonarfy.config;

import br.com.pablohcarmo.sonarfy.services.*;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.common.policy.RequestRetryOptions;
import com.azure.storage.common.policy.RetryPolicyType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.servlet.MultipartConfigFactory;
import org.springframework.util.unit.DataSize;

@Configuration
@ConditionalOnProperty(name = "app.avatar.enabled", havingValue = "true")
@EnableConfigurationProperties(AvatarProperties.class)
@Import({AvatarImageProcessor.class, AvatarUploadService.class})
public class AvatarConfig {
    // Aplica os limites no contêiner servlet e no processamento da imagem.
    @Bean
    MultipartConfigElement avatarMultipartConfig(AvatarProperties properties) {
        var factory = new MultipartConfigFactory();
        factory.setMaxFileSize(DataSize.ofBytes(properties.maxBytes()));
        factory.setMaxRequestSize(DataSize.ofBytes((long) properties.maxBytes() + 65536));
        return factory.createMultipartConfig();
    }

    // Usa identidade gerenciada no Azure ou sessão Entra local do desenvolvedor.
    @Bean
    AzureAvatarStorage avatarStorage(AvatarProperties properties) {
        var client = new BlobContainerClientBuilder().endpoint(properties.endpoint().replaceAll("/+$", "") + "/" + properties.container())
                .credential(new DefaultAzureCredentialBuilder().build())
                .retryOptions(new RequestRetryOptions(RetryPolicyType.FIXED, 1, properties.timeoutSeconds(), null, null, null))
                .buildClient();
        return new AzureAvatarStorage(client, properties.timeoutSeconds());
    }
}
