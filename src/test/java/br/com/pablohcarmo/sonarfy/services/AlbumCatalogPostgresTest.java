package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.exceptions.SpotifyApiException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

// Banco descartável dedicado e Feign real contra a simulação Postman versionada.
@EnabledIfSystemProperty(named = "sonarfy.test.catalog-postgres", matches = "true")
@SpringJUnitConfig({SpotifyRecentlyPlayedSimulationTest.Config.class, AlbumCatalogPostgresTest.Config.class})
class AlbumCatalogPostgresTest {
    static final String ID = "4aawyAB9vmqN3uQ7FjRGTy";
    @Configuration @EnableTransactionManagement
    @Import({AlbumCatalogStore.class, AlbumCatalogService.class, SpotifyAlbumService.class})
    static class Config {
        @Bean DataSource dataSource() {
            String url = System.getenv().getOrDefault("SONARFY_CATALOG_TEST_JDBC_URL", "");
            if (!url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1):[0-9]+/sonarfy_catalog_validation"))
                throw new IllegalArgumentException("Dedicated local catalog validation database required.");
            return new DriverManagerDataSource(url, System.getenv().getOrDefault("SONARFY_TEST_DB_USER", "postgres"),
                    System.getenv().getOrDefault("SONARFY_TEST_DB_PASSWORD", ""));
        }
        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean DataSourceTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("spotify.api-url", () -> System.getProperty("sonarfy.test.spotify-api-url", "http://127.0.0.1:55442/spotify-api"));
    }
    @Autowired AlbumCatalogService catalog;
    @Autowired AlbumCatalogStore store;
    @Autowired JdbcTemplate db;
    @Autowired DataSource ds;
    @Autowired DataSourceTransactionManager transactions;
    @BeforeEach void clear() { db.execute("TRUNCATE artists CASCADE"); }

    // Reutiliza registros de Ecos e vínculos de avaliações, sem obter token no cache hit.
    @Test void enrichesExistingAlbumAndPreservesIdentity() {
        db.update("INSERT INTO artists (spotify_id, name) VALUES ('artist1', 'Artist One')");
        db.update("INSERT INTO albums (artist_id, spotify_id, name) SELECT artist_id, ?, 'Legacy' FROM artists", ID);
        long id = store.find(ID).orElseThrow().id();
        db.update("""
                INSERT INTO users (permission_id, name, surname, display_name, handle, email, password_hash,
                    city, country, birth_date) SELECT permission_id, 'A', 'B', 'A B', 'catalog',
                    'catalog@example.com', 'hash', 'City', 'Country', '2000-01-01' FROM permissions
                WHERE permission = 'ROLE_USER' ON CONFLICT DO NOTHING
                """);
        db.update("INSERT INTO reviews (album_id, user_id, rating) SELECT ?, user_id, 8 FROM users WHERE handle = 'catalog'", id);
        var result = new TransactionTemplate(transactions).execute(s -> catalog.resolve(ID, () -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
            return "token-ok";
        }, "BR"));
        assertEquals(id, result.id());
        assertEquals(70, result.totalTracks());
        assertEquals("2024-05", result.releaseOn());
        assertEquals("mes", result.releasePrecision());
        assertEquals(1, db.queryForObject("SELECT count(*) FROM reviews WHERE album_id = ?", Integer.class, id));
        assertEquals(result, catalog.resolve(ID, () -> { throw new AssertionError("Unexpected credential request"); }, "BR"));
    }

    // Duas buscas simultâneas usam a mesma identidade, sem duplicar artista ou álbum.
    @Test void concurrentMissesAreIdempotent() throws Exception {
        var barrier = new CyclicBarrier(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<AlbumCatalogStore.Album> resolve = () -> catalog.resolve(ID, () -> {
                try { barrier.await(10, TimeUnit.SECONDS); } catch (Exception e) { throw new AssertionError(e); }
                return "token-ok";
            }, "BR");
            var first = pool.submit(resolve);
            var second = pool.submit(resolve);
            assertEquals(first.get(20, TimeUnit.SECONDS).id(), second.get(20, TimeUnit.SECONDS).id());
        }
        assertEquals(1, db.queryForObject("SELECT count(*) FROM artists", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM albums", Integer.class));
    }

    // Falha remota e falha de banco não deixam artistas órfãos ou catálogo parcial.
    @Test void failuresLeaveNoPartialCatalog() {
        assertThrows(SpotifyApiException.class, () -> catalog.resolve(ID, () -> "token-503", "BR"));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM artists", Integer.class));
        db.execute("ALTER TABLE albums ADD CONSTRAINT catalog_test_failure CHECK (name <> 'Album One')");
        try {
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                    () -> catalog.resolve(ID, () -> "token-ok", "BR"));
            assertEquals(0, db.queryForObject("SELECT count(*) FROM artists", Integer.class));
            assertTrue(store.find(ID).isEmpty());
        } finally { db.execute("ALTER TABLE albums DROP CONSTRAINT catalog_test_failure"); }
    }

    // Migration repetível preserva registros antigos e rejeita contagem negativa.
    @Test void migrationIsRepeatableAndPreservesLegacyData() {
        db.update("INSERT INTO artists (spotify_id, name) VALUES ('artist1', 'Legacy')");
        db.update("INSERT INTO albums (artist_id, spotify_id, name) SELECT artist_id, ?, 'Legacy' FROM artists", ID);
        long id = store.find(ID).orElseThrow().id();
        db.execute("ALTER TABLE albums DROP COLUMN total_tracks");
        var migration = new ResourceDatabasePopulator(new FileSystemResource("db/migrations/017_add_album_total_tracks.sql"));
        migration.execute(ds);
        migration.execute(ds);
        assertEquals(id, store.find(ID).orElseThrow().id());
        assertNull(store.find(ID).orElseThrow().totalTracks());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> db.update("UPDATE albums SET total_tracks = -1"));
    }
}
