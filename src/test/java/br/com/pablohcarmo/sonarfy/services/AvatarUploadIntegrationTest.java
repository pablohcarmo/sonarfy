package br.com.pablohcarmo.sonarfy.services;

import br.com.pablohcarmo.sonarfy.config.AvatarProperties;
import br.com.pablohcarmo.sonarfy.controllers.AvatarController;
import br.com.pablohcarmo.sonarfy.entities.*;
import br.com.pablohcarmo.sonarfy.repositories.*;
import com.azure.storage.blob.BlobContainerClientBuilder;
import com.azure.storage.common.policy.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import javax.imageio.ImageIO;
import javax.sql.DataSource;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// Segurança, JPA e SDK Azure reais; servidor local simula envio, leitura e exclusão de blobs.
@SpringJUnitWebConfig(AvatarUploadIntegrationTest.Config.class)
@TestPropertySource(properties = {"api.security.token.secret=avatar-test-secret", "app.avatar.enabled=true",
        "app.frontend-url=http://localhost:3000", "spring.mail.username=noreply@example.com"})
class AvatarUploadIntegrationTest {
    static final Map<String, byte[]> blobs = new ConcurrentHashMap<>();
    static volatile int putStatus = 201, deleteStatus = 202, calls, putDelayMillis, deleteCalls;
    static HttpServer server;
    static {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/images/", exchange -> {
                String path = exchange.getRequestURI().getPath(); int status = 501; byte[] response = new byte[0];
                switch (exchange.getRequestMethod()) {
                    case "PUT" -> {
                        calls++; byte[] data = exchange.getRequestBody().readAllBytes(); status = putStatus;
                        if (status == 201) {
                            assertEquals("image/png", exchange.getRequestHeaders().getFirst("x-ms-blob-content-type"));
                            assertEquals("*", exchange.getRequestHeaders().getFirst("If-None-Match"));
                            blobs.put(path, data);
                        }
                        try { Thread.sleep(putDelayMillis); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                    }
                    case "DELETE" -> { deleteCalls++; status = deleteStatus; if (status == 202) blobs.remove(path); }
                    case "GET" -> { response = blobs.getOrDefault(path, new byte[0]); status = response.length > 0 ? 200 : 404; }
                }
                exchange.getResponseHeaders().set("ETag", "\"test-etag\"");
                exchange.getResponseHeaders().set("x-ms-request-id", "test-request");
                exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
                if (response.length > 0) exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
        } catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @Configuration
    @Import({AvatarController.class, AvatarUploadService.class, AvatarImageProcessor.class})
    static class Config extends ProfileIntegrationTest.Config {
        @Bean @Override DataSource dataSource(org.springframework.core.env.Environment env) {
            if (Boolean.getBoolean("sonarfy.test.avatar-postgres"))
                return new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:"
                        + Integer.getInteger("sonarfy.test.avatar-port", 55450) + "/sonarfy_avatar_test", "avatar_test", "isolated-avatar-test");
            return new DriverManagerDataSource("jdbc:h2:mem:avatar;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        }
        @Bean @Override org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = super.entityManagerFactory(ds);
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto",
                    Boolean.getBoolean("sonarfy.test.avatar-postgres") ? "validate" : "create-drop"));
            return factory;
        }
        @Bean AvatarProperties avatarProperties() { return new AvatarProperties(null, "images", 5242880, 16000000, 512, 2); }
        @Bean AzureAvatarStorage avatarStorage() {
            var container = new BlobContainerClientBuilder().endpoint("http://127.0.0.1:" + server.getAddress().getPort() + "/images")
                    .retryOptions(new RequestRetryOptions(RetryPolicyType.FIXED, 1, 2, null, null, null)).buildClient();
            return new AzureAvatarStorage(container, 2);
        }
    }
    @Autowired WebApplicationContext context;
    @MockitoSpyBean UserRepository users;
    @Autowired PermissionRepository permissions;
    @Autowired JwtService jwt;
    MockMvc mvc;
    User actor;

    @BeforeEach
    void prepare() {
        blobs.clear(); putStatus = 201; deleteStatus = 202; calls = 0; putDelayMillis = 0; deleteCalls = 0;
        users.deleteAll();
        new org.springframework.jdbc.core.JdbcTemplate(context.getBean(DataSource.class))
                .execute("ALTER TABLE users ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP");
        var role = permissions.findByName("ROLE_USER").orElseGet(() -> {
            var value = new Permission(); value.setName("ROLE_USER"); return permissions.save(value);
        });
        actor = users.saveAndFlush(new User(null, role, "Private", "Account", "Public name", "owner", "owner@example.com",
                "test-hash", true, true, "Brasilia", "Brasil", "https://legacy.example/avatar", "wallpaper", "bio",
                LocalDate.of(2000, 1, 1), null, null, null, null));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterAll static void stop() { server.stop(0); }

    // Ignora nomes e IDs enviados; substitui somente versões gerenciadas do próprio usuário.
    @Test
    void uploadsPublicPngAndReplacesOnlyAfterCommit() throws Exception {
        upload(image("PNG")).andExpect(status().isOk()).andExpect(jsonPath("$.identity.id").value(actor.getId()))
                .andExpect(jsonPath("$.viewer.isSelf").value(true)).andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"));
        String first = users.findById(actor.getId()).orElseThrow().getAvatar();
        assertTrue(URI.create(first).getPath().contains("/avatars/" + actor.getId() + "/"));
        try (var stream = URI.create(first).toURL().openStream()) {
            var decoded = ImageIO.read(stream); assertEquals(512, decoded.getWidth()); assertEquals(512, decoded.getHeight());
        }
        upload(image("JPEG")).andExpect(status().isOk());
        assertNotEquals(first, users.findById(actor.getId()).orElseThrow().getAvatar());
        assertFalse(blobs.containsKey(URI.create(first).getPath())); assertEquals(1, blobs.size());
        assertEquals("wallpaper", users.findById(actor.getId()).orElseThrow().getWallpaper());
    }

    // Falha no armazenamento ou rollback preserva o avatar anterior e limpa a nova versão.
    @Test
    void preservesPreviousOnStorageOrDatabaseFailure() throws Exception {
        upload(image("PNG")).andExpect(status().isOk());
        String previous = users.findById(actor.getId()).orElseThrow().getAvatar();
        putStatus = 500;
        upload(image("PNG")).andExpect(status().isServiceUnavailable()); assertEquals(2, calls);
        putStatus = 201;
        doThrow(new org.springframework.dao.DataIntegrityViolationException("test rollback")).when(users).saveAndFlush(any(User.class));
        assertThrows(Exception.class, () -> upload(image("PNG")));
        assertEquals(previous, users.findById(actor.getId()).orElseThrow().getAvatar()); assertEquals(1, blobs.size());
    }

    // Falha na limpeza após commit preserva o novo avatar e a resposta de sucesso.
    @Test
    void retainsSuccessfulUpdateWhenOldBlobDeletionFails() throws Exception {
        upload(image("PNG")).andExpect(status().isOk());
        String previous = users.findById(actor.getId()).orElseThrow().getAvatar(); deleteStatus = 500;
        upload(image("PNG")).andExpect(status().isOk());
        assertNotEquals(previous, users.findById(actor.getId()).orElseThrow().getAvatar()); assertEquals(2, blobs.size());
    }

    // Timeout preserva a referência atual e não provoca repetição automática do upload.
    @Test
    void preservesReferenceOnStorageTimeout() throws Exception {
        putDelayMillis = 3000;
        upload(image("PNG")).andExpect(status().isServiceUnavailable());
        assertEquals(1, calls);
        assertEquals("https://legacy.example/avatar", users.findById(actor.getId()).orElseThrow().getAvatar());
    }

    // Conflito de criação não exclui um objeto existente.
    @ParameterizedTest @ValueSource(ints = {409, 412})
    void doesNotDeleteOnCreateConflict(int conflict) throws Exception {
        putStatus = conflict;
        upload(image("PNG")).andExpect(status().isServiceUnavailable());
        assertEquals(1, calls); assertEquals(0, deleteCalls);
    }

    // Substituições concorrentes serializam a referência e usam blobs distintos.
    @Test
    void serializesConcurrentReplacements() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> task = () -> { start.await(); return upload(image("PNG")).andReturn().getResponse().getStatus(); };
            var first = pool.submit(task); var second = pool.submit(task); start.countDown();
            assertEquals(200, first.get(15, TimeUnit.SECONDS)); assertEquals(200, second.get(15, TimeUnit.SECONDS));
        }
        String current = users.findById(actor.getId()).orElseThrow().getAvatar();
        assertEquals(1, blobs.size()); assertTrue(blobs.containsKey(URI.create(current).getPath()));
    }

    // Aplica as oito orientações EXIF e gera PNG sem os metadados originais.
    @ParameterizedTest @ValueSource(ints = {1,2,3,4,5,6,7,8})
    void appliesExifOrientation(int orientation) throws Exception {
        byte[] jpeg = image("JPEG"), exif = new byte[32];
        ByteBuffer tiff = ByteBuffer.wrap(exif).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put(new byte[]{'E','x','i','f',0,0}).putShort((short) 0x4949).putShort((short) 42).putInt(8)
                .putShort((short) 1).putShort((short) 0x112).putShort((short) 3).putInt(1)
                .putShort((short) orientation).putShort((short) 0).putInt(0);
        byte[] source = concat(Arrays.copyOf(jpeg, 2), new byte[]{(byte) 0xff, (byte) 0xe1, 0, 34}, exif, Arrays.copyOfRange(jpeg, 2, jpeg.length));
        upload(source).andExpect(status().isOk());
        byte[] png = blobs.values().iterator().next();
        assertNull(com.drew.imaging.ImageMetadataReader.readMetadata(new ByteArrayInputStream(png))
                .getFirstDirectoryOfType(com.drew.metadata.exif.ExifIFD0Directory.class));
        int[] x = {128,384,384,128,128,384,384,128}, y = {128,128,384,384,128,128,384,384};
        int pixel = ImageIO.read(new ByteArrayInputStream(png)).getRGB(x[orientation - 1], y[orientation - 1]);
        assertTrue(((pixel >> 16) & 255) > 220 && (pixel & 255) < 30);
        mvc.perform(multipart("/api/profile/me/avatar").file(new MockMultipartFile("file", source))
                .header("Authorization", bearer()).param("cropX", x[orientation - 1] == 128 ? "0" : "32")
                .param("cropY", y[orientation - 1] == 128 ? "0" : "32").param("cropSize", "32"))
                .andExpect(status().isOk());
        pixel = ImageIO.read(new ByteArrayInputStream(blobs.values().iterator().next())).getRGB(256, 256);
        assertTrue(((pixel >> 16) & 255) > 220 && (pixel & 255) < 30);
    }

    // Rejeita conteúdo inválido e dimensões excedidas antes de chamar o armazenamento.
    @ParameterizedTest @ValueSource(strings = {"empty", "svg", "bytes", "pixels", "animated", "corrupt"})
    void rejectsInvalidFilesBeforeStorage(String type) throws Exception {
        byte[] data = image("PNG"); int status = 400;
        switch (type) {
            case "empty" -> data = new byte[0];
            case "svg" -> { data = "<svg/>".getBytes(); status = 415; }
            case "bytes" -> { data = new byte[5242881]; status = 413; }
            case "pixels" -> { ByteBuffer.wrap(data, 16, 8).putInt(5000).putInt(5000); }
            case "animated" -> { byte[] chunk = new byte[12]; ByteBuffer.wrap(chunk).putInt(0).putInt(0x6163544c); data = concat(Arrays.copyOf(data, 33), chunk, Arrays.copyOfRange(data, 33, data.length)); }
            case "corrupt" -> data = Arrays.copyOf(data, 40);
        }
        upload(data).andExpect(status().is(status)); assertEquals(0, calls);
        assertEquals("https://legacy.example/avatar", users.findById(actor.getId()).orElseThrow().getAvatar());
    }

    // A segurança real rejeita credenciais ausentes ou de outro propósito antes do controller.
    @Test
    void requiresAccessTokenAndFile() throws Exception {
        var file = new MockMultipartFile("file", "avatar.png", "image/png", image("PNG"));
        mvc.perform(multipart("/api/profile/me/avatar").file(file)).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/profile/me/avatar").file(file).header("Authorization", "Bearer " + jwt.generateRegistrationToken(actor.getId().toString())))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/profile/me/avatar").header("Authorization", bearer())).andExpect(status().isBadRequest());
        actor.setActive(false); users.saveAndFlush(actor);
        mvc.perform(multipart("/api/profile/me/avatar").file(file).header("Authorization", bearer())).andExpect(status().isUnauthorized());
        assertEquals(0, calls);
    }

    // Recorte central e seleção explícita preservam proporções; coordenadas inválidas não chegam ao Azure.
    @Test
    void cropsRectangularImages() throws Exception {
        var picture = new BufferedImage(96, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 96; x++) picture.setRGB(x, y, x < 48 ? 0xff0000 : 0x0000ff);
        var output = new ByteArrayOutputStream(); ImageIO.write(picture, "PNG", output);
        upload(output.toByteArray()).andExpect(status().isOk());
        var centered = ImageIO.read(new ByteArrayInputStream(blobs.values().iterator().next()));
        assertEquals(0xff0000, centered.getRGB(128, 256) & 0xffffff);
        assertEquals(0x0000ff, centered.getRGB(384, 256) & 0xffffff);
        var portrait = new BufferedImage(64, 96, BufferedImage.TYPE_INT_RGB);
        var portraitBytes = new ByteArrayOutputStream(); ImageIO.write(portrait, "PNG", portraitBytes);
        upload(portraitBytes.toByteArray()).andExpect(status().isOk());
        var request = multipart("/api/profile/me/avatar").file(new MockMultipartFile("file", output.toByteArray()))
                .header("Authorization", bearer()).param("cropX", "48").param("cropY", "0").param("cropSize", "48");
        mvc.perform(request).andExpect(status().isOk());
        assertEquals(0x0000ff, ImageIO.read(new ByteArrayInputStream(blobs.values().iterator().next())).getRGB(256, 256) & 0xffffff);
        for (String[] crop : new String[][]{{"-1","0","32"},{"0","0","0"},{"80","0","32"},{"2147483647","0","32"}}) {
            mvc.perform(multipart("/api/profile/me/avatar").file(new MockMultipartFile("file", output.toByteArray()))
                    .header("Authorization", bearer()).param("cropX", crop[0]).param("cropY", crop[1]).param("cropSize", crop[2]))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(multipart("/api/profile/me/avatar").file(new MockMultipartFile("file", output.toByteArray()))
                .header("Authorization", bearer()).param("cropX", "0")).andExpect(status().isBadRequest());
        assertEquals(3, calls);
    }

    private org.springframework.test.web.servlet.ResultActions upload(byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/profile/me/avatar").file(new MockMultipartFile("file", "../../not-a-key.png", "image/png", bytes))
                .param("userId", "9999").header("Authorization", bearer()));
    }
    private String bearer() { return "Bearer " + jwt.generateAccessToken(actor.getId().toString()); }
    private static byte[] image(String format) throws IOException {
        var image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) image.setRGB(x, y, x < 32 && y < 32 ? 0xff0000 : 0x0000ff);
        var output = new ByteArrayOutputStream(); ImageIO.write(image, format, output); return output.toByteArray();
    }
    private static byte[] concat(byte[]... parts) throws IOException {
        var output = new ByteArrayOutputStream(); for (byte[] part : parts) output.write(part); return output.toByteArray();
    }
}
