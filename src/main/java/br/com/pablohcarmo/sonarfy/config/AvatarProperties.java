package br.com.pablohcarmo.sonarfy.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.avatar")
public record AvatarProperties(
        @Pattern(regexp = "https://[a-z0-9]{3,24}\\.blob\\.core\\.windows\\.net/?") @NotNull String endpoint,
        @DefaultValue("images") @Pattern(regexp = "(?!.*--)[a-z0-9][a-z0-9-]{1,61}[a-z0-9]") String container,
        @DefaultValue("5242880") @Min(1) @Max(2147483646) int maxBytes,
        @DefaultValue("16000000") @Min(1) long maxPixels,
        @DefaultValue("512") @Min(1) @Max(4096) int size,
        @DefaultValue("10") @Min(1) @Max(120) int timeoutSeconds) {}
