package dev.matheus.provider.service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import dev.matheus.provider.config.SimulatorProperties;
import dev.matheus.provider.model.EnrichmentRequest;
import dev.matheus.provider.model.EnrichmentResult;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Devolve o resultado chamando {@code POST {WEBHOOK_URL}/webhooks/enrichment-results} (spec, seções 2.5 e 6).
 * O corpo é serializado e assinado uma única vez; os reenvios mandam exatamente os mesmos bytes. {@code 5xx}, timeout
 * e conexão recusada são reenviados (esperas configuráveis, 1s, 2s e 4s por padrão); {@code 4xx} não é reenviado.
 * As esperas são agendadas, sem bloquear threads com {@code sleep}. Logs sem CNPJ, valor, segredo ou assinatura.
 */
@Slf4j
@Component
public class WebhookCallbackSender implements DisposableBean {

    private static final String PATH = "/webhooks/enrichment-results";
    private static final String SIGNATURE_HEADER = "X-Signature";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final RestClient restClient;
    private final HmacSigner signer;
    private final JsonMapper jsonMapper;
    private final Duration delay;
    private final List<Duration> retryWaits;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public WebhookCallbackSender(SimulatorProperties properties, HmacSigner signer, JsonMapper jsonMapper) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.callback().connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.callback().readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.webhookUrl())
                .requestFactory(requestFactory)
                .build();
        this.signer = signer;
        this.jsonMapper = jsonMapper;
        this.delay = properties.delay();
        this.retryWaits = List.copyOf(properties.callback().retryWaits());
    }

    /** Agenda o callback com um resultado aprovável, depois do atraso configurado. Não bloqueia a requisição. */
    public void scheduleApprovableResult(EnrichmentRequest request) {
        EnrichmentResult result = EnrichmentResult.approvableFor(request, Instant.now());
        byte[] body = jsonMapper.writeValueAsBytes(result);
        Callback callback = new Callback(result.messageId(), result.correlationId(), body, signer.sign(body));
        schedule(callback, 0, delay);
    }

    private void schedule(Callback callback, int attempt, Duration wait) {
        scheduler.schedule(() -> send(callback, attempt), wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void send(Callback callback, int attempt) {
        try {
            restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(SIGNATURE_HEADER, callback.signature())
                    .header(CORRELATION_HEADER, callback.correlationId().toString())
                    .body(callback.body())
                    .retrieve()
                    .toBodilessEntity();
            log.info("Callback entregue ao webhook: messageId={}, correlationId={}, envio={}",
                    callback.messageId(), callback.correlationId(), attempt + 1);
        } catch (HttpClientErrorException e) {
            log.warn("Webhook recusou o callback, sem reenvio: messageId={}, correlationId={}, status={}",
                    callback.messageId(), callback.correlationId(), e.getStatusCode().value());
        } catch (HttpServerErrorException e) {
            retryOrGiveUp(callback, attempt, "status " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            retryOrGiveUp(callback, attempt, "timeout ou conexão recusada");
        } catch (RestClientResponseException e) {
            log.warn("Resposta inesperada do webhook, sem reenvio: messageId={}, correlationId={}, status={}",
                    callback.messageId(), callback.correlationId(), e.getStatusCode().value());
        } catch (RuntimeException e) {
            log.error("Erro inesperado ao enviar o callback: messageId={}, correlationId={}",
                    callback.messageId(), callback.correlationId(), e);
        }
    }

    private void retryOrGiveUp(Callback callback, int attempt, String reason) {
        if (attempt < retryWaits.size()) {
            Duration wait = retryWaits.get(attempt);
            log.warn("Webhook indisponível ({}), reenvio {} de {} em {} ms: messageId={}, correlationId={}",
                    reason, attempt + 1, retryWaits.size(), wait.toMillis(), callback.messageId(),
                    callback.correlationId());
            schedule(callback, attempt + 1, wait);
        } else {
            log.error("Reenvios do callback esgotados ({}): messageId={}, correlationId={}",
                    reason, callback.messageId(), callback.correlationId());
        }
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }

    private record Callback(UUID messageId, UUID correlationId, byte[] body, String signature) {
    }
}
