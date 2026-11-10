package dev.matheus.invoice.integration.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Provedor externo de teste (servidor HTTP do JDK). Em {@link Mode#ACCEPT} imita o simulador: responde {@code 202} e,
 * em outra thread, devolve o resultado aprovável assinado chamando o webhook, com o {@code messageId} derivado do da
 * solicitação. Registra cada solicitação recebida.
 */
public final class FakeProvider implements AutoCloseable {

    public enum Mode {
        /** 202 e depois o callback assinado ao webhook. */
        ACCEPT,
        /** 202 sem callback: o teste envia o callback por conta própria. */
        SILENT,
        /** 503 em toda chamada. */
        SERVER_ERROR
    }

    public record Call(String body, String correlationId, String contentType) {
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String secret;

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final List<Integer> callbackStatuses = new CopyOnWriteArrayList<>();
    private volatile Mode mode = Mode.ACCEPT;
    private volatile String webhookUrl;

    public FakeProvider(String secret) {
        this.secret = secret;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/enrichment-requests", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(new Call(body, exchange.getRequestHeaders().getFirst("X-Correlation-Id"),
                    exchange.getRequestHeaders().getFirst("Content-Type")));
            Mode current = mode;
            exchange.sendResponseHeaders(current == Mode.SERVER_ERROR ? 503 : 202, -1);
            exchange.close();
            if (current == Mode.ACCEPT) {
                executor.submit(() -> callWebhook(body));
            }
        });
        server.setExecutor(executor);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void webhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public void mode(Mode mode) {
        this.mode = mode;
    }

    public List<Call> calls() {
        return calls;
    }

    public List<Integer> callbackStatuses() {
        return callbackStatuses;
    }

    public void reset() {
        calls.clear();
        callbackStatuses.clear();
        mode = Mode.ACCEPT;
    }

    private void callWebhook(String requestBody) {
        try {
            JsonNode request = json.readTree(requestBody);
            UUID requestMessageId = UUID.fromString(request.get("messageId").asString());
            UUID correlationId = UUID.fromString(request.get("correlationId").asString());
            String accessKey = request.get("payload").get("accessKey").asString();
            String body = IntegrationTestSupport.approvableResultJson(
                    IntegrationTestSupport.resultMessageIdFor(requestMessageId), correlationId, accessKey);
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(webhookUrl))
                    .header("Content-Type", "application/json")
                    .header("X-Signature", IntegrationTestSupport.sign(body, secret))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
            callbackStatuses.add(response.statusCode());
        } catch (Exception e) {
            callbackStatuses.add(-1);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
