package br.com.pablohcarmo.sonarfy.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.email-change.enabled", havingValue = "true")
public class EmailChangeSchedulingConfig {}
