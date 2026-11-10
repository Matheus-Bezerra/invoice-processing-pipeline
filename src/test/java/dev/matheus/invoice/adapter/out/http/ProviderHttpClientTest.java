package dev.matheus.invoice.adapter.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.sun.net.httpserver.HttpServer;

import dev.matheus.invoice.application.port.out.provider.ProviderRejectedException;
import dev.matheus.invoice.application.port.out.provider.ProviderUnavailableException;

/** Client HTTP contra um provedor fake (servidor HTTP do JDK). */
class ProviderHttpClientTest {

    private static final UUID CORRELATION_ID = UUID.randomUUID();
    private static final String BODY = "{\"messageId\":\"6a1f0c58-3f2e-4b8e-9d1b-0d6b6e2c9a10\",\"payload\":{}}";

    private HttpServer server;
    private volatile int status = 202;
    private volatile long delayMillis = 0;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> correlationHeader = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    private ProviderHttpClient client;

    @BeforeEach
    void startProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/enrichment-requests", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            correlationHeader.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                exchange.sendResponseHeaders(status, -1);
            } catch (IOException ignored) {
                // o cliente já desistiu (timeout)
            } finally {
                exchange.close();
            }
        });
        server.start();
        client = clientFor(server.getAddress().getPort(), Duration.ofSeconds(1));
    }

    @AfterEach
    void stopProvider() {
        server.stop(0);
    }

    private static ProviderHttpClient clientFor(int port, Duration readTimeout) {
        return new ProviderHttpClient("http://127.0.0.1:" + port, Duration.ofSeconds(1), readTimeout);
    }

    @Test
    void acceptedRequestSendsTheExactBodyAndHeaders() {
        client.requestEnrichment(CORRELATION_ID, BODY);

        assertThat(method.get()).isEqualTo("POST");
        assertThat(path.get()).isEqualTo("/enrichment-requests");
        assertThat(contentType.get()).startsWith("application/json");
        assertThat(correlationHeader.get()).isEqualTo(CORRELATION_ID.toString());
        assertThat(receivedBody.get()).isEqualTo(BODY);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 422})
    void clientErrorIsAPermanentRejection(int code) {
        status = code;

        assertThatThrownBy(() -> client.requestEnrichment(CORRELATION_ID, BODY))
                .isInstanceOfSatisfying(ProviderRejectedException.class, e -> assertThat(e.status()).isEqualTo(code));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503})
    void serverErrorIsTechnicalFailure(int code) {
        status = code;

        assertThatThrownBy(() -> client.requestEnrichment(CORRELATION_ID, BODY))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void slowResponseIsTechnicalFailure() {
        delayMillis = 1_500;
        ProviderHttpClient impatient = clientFor(server.getAddress().getPort(), Duration.ofMillis(200));

        assertThatThrownBy(() -> impatient.requestEnrichment(CORRELATION_ID, BODY))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void connectionRefusedIsTechnicalFailure() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        ProviderHttpClient offline = clientFor(closedPort, Duration.ofSeconds(1));

        assertThatThrownBy(() -> offline.requestEnrichment(CORRELATION_ID, BODY))
                .isInstanceOf(ProviderUnavailableException.class);
    }

    @Test
    void failureMessagesDoNotCarryTheRequestBody() {
        status = 503;

        assertThatThrownBy(() -> client.requestEnrichment(CORRELATION_ID, BODY))
                .hasMessageNotContaining("messageId");
    }
}
