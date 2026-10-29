package br.com.pablohcarmo.sonarfy.client;

import com.sun.net.httpserver.HttpServer;
import feign.FeignException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(AuthSpotifyClientTest.Config.class)
@Timeout(10)
class AuthSpotifyClientTest {

    @Configuration
    @EnableFeignClients(clients = AuthSpotifyClient.class)
    @ImportAutoConfiguration({FeignAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class})
    static class Config {}

    private static HttpServer server;
    private static final LinkedBlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private static volatile int responseStatus;
    private static final String AUTHORIZATION = "Basic dGVzdC1jbGllbnQ6dGVzdC1zZWNyZXQ=";

    private record Request(String method, String path, String authorization,
                           String contentType, String body) {}

    @Autowired
    private AuthSpotifyClient client;

    @BeforeAll
    static void startServer() throws Exception {
        // Porta dinâmica e loopback: nenhuma chamada é enviada ao Spotify.
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(new Request(exchange.getRequestMethod(),
                        exchange.getRequestURI().toString(),
                        exchange.getRequestHeaders().getFirst("Authorization"),
                        exchange.getRequestHeaders().getFirst("Content-Type"),
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                String body = responseStatus == 200 ? """
                        {"access_token":"test-access", "token_type":"Bearer",
                         "expires_in":3600, "refresh_token":"test-refresh"}
                        """ : "{\"error\":\"invalid_grant\"}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }

    @DynamicPropertySource
    static void configureUrl(DynamicPropertyRegistry registry) {
        registry.add("spotify.auth-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @BeforeEach
    void resetResponse() {
        requests.clear();
        responseStatus = 200;
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void shouldSendFormAndDeserializeSpotifyResponse() throws Exception {
        // Caracteres especiais detectam erros de codificação ou dupla codificação.
        var form = Map.of("grant_type", "authorization_code", "code", "code+&= ç",
                "redirect_uri", "https://example.com/callback?source=sonarfy&mode=test");

        var response = client.exchangeCode(AUTHORIZATION, form);

        var request = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("POST", request.method());
        assertEquals("/api/token", request.path());
        assertEquals(AUTHORIZATION, request.authorization());
        assertNotNull(request.contentType());
        assertTrue(request.contentType().startsWith("application/x-www-form-urlencoded"));
        var decodedForm = Arrays.stream(request.body().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8)));
        assertEquals(form, decodedForm);
        assertEquals("test-access", response.accessToken());
        assertEquals("Bearer", response.tokenType());
        assertEquals(3600L, response.expiresIn());
        assertEquals("test-refresh", response.refreshToken());
    }

    @Test
    void shouldPropagateTokenRejectionAsFeignException() {
        responseStatus = 400;
        var exception = assertThrows(FeignException.class, () -> client.exchangeCode(
                AUTHORIZATION, Map.of("grant_type", "authorization_code", "code", "expired-code",
                        "redirect_uri", "https://example.com/callback")));
        assertEquals(400, exception.status());
        assertEquals(1, requests.size());
    }
}
