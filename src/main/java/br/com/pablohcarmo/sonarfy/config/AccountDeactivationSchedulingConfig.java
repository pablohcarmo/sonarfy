package br.com.pablohcarmo.sonarfy.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.account-deactivation.enabled", havingValue = "true")
public class AccountDeactivationSchedulingConfig {}
