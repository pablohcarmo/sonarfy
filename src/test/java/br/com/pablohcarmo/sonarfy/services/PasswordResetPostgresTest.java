package br.com.pablohcarmo.sonarfy.services;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

// Repete validação, rollback e concorrência no schema PostgreSQL importado, sem DDL automático.
@EnabledIfSystemProperty(named = "sonarfy.test.existing-schema", matches = "true")
@SpringJUnitConfig(RefreshTokenRepositoryServiceTest.Config.class)
@TestPropertySource(properties = {
        "sonarfy.test.jdbc-url=${SONARFY_TEST_JDBC_URL}",
        "sonarfy.refresh.test.ddl=validate"
})
class PasswordResetPostgresTest extends PasswordResetIntegrationTest {
}
