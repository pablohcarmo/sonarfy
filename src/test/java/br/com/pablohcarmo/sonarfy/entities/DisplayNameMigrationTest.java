package br.com.pablohcarmo.sonarfy.entities;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DisplayNameMigrationTest {
    @Test
    void backfillsExistingUsersAndPreservesCustomNamesOnRerun() {
        var dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE Users (user_id BIGINT PRIMARY KEY, name VARCHAR(255), surname VARCHAR(255))");
        jdbc.update("INSERT INTO Users VALUES (1, 'Ana', 'Silva'), (2, 'João', NULL)");
        var migration = new ResourceDatabasePopulator(
                new FileSystemResource("docs/database/add-user-display-name.sql"));

        migration.execute(dataSource);
        assertEquals("Ana Silva", jdbc.queryForObject(
                "SELECT display_name FROM Users WHERE user_id = 1", String.class));
        assertEquals("João", jdbc.queryForObject(
                "SELECT display_name FROM Users WHERE user_id = 2", String.class));

        jdbc.update("UPDATE Users SET display_name = 'Nome escolhido' WHERE user_id = 1");
        migration.execute(dataSource);
        assertEquals("Nome escolhido", jdbc.queryForObject(
                "SELECT display_name FROM Users WHERE user_id = 1", String.class));
    }
}
