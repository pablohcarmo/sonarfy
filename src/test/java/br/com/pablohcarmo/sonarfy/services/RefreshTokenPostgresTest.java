package br.com.pablohcarmo.sonarfy.services;

import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.context.TestPropertySource;

// Executa os mesmos cenários no schema migrado, sem criar ou remover tabelas.
@EnabledIfSystemProperty(named = "sonarfy.test.existing-schema", matches = "true")
@TestPropertySource(properties = {
        "sonarfy.test.jdbc-url=${SONARFY_TEST_JDBC_URL}",
        "sonarfy.refresh.test.ddl=validate"
})
class RefreshTokenPostgresTest extends RefreshTokenRepositoryServiceTest {
}
