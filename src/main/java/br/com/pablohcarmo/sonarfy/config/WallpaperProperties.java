package br.com.pablohcarmo.sonarfy.config;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.wallpaper")
public record WallpaperProperties(@DefaultValue("1600") @Min(1) @Max(8192) int maxWidth,
                                  @DefaultValue("600") @Min(1) @Max(8192) int maxHeight) {}
