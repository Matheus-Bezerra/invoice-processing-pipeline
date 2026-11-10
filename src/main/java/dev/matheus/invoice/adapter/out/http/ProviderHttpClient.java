package dev.matheus.invoice.adapter.out.http;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import dev.matheus.invoice.application.port.out.provider.ProviderClient;
import dev.matheus.invoice.application.port.out.provider.ProviderRejectedException;
import dev.matheus.invoice.application.port.out.provider.ProviderUnavailableException;

/**
 * Chama {@code POST /enrichment-requests} no provedor (spec, seção 3.4). {@code 4xx} é recusa permanente, menos
 * {@code 408} e {@code 429}, que são temporários; esses dois, {@code 5xx}, redirecionamento, timeout e conexão recusada
 * são falha técnica. As mensagens de erro não levam o corpo da
 * requisição nem o da resposta.
 */
@Component
@Profile("worker")
class ProviderHttpClient implements ProviderClient {

    private static final String PATH = "/enrichment-requests";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final RestClient restClient;

    ProviderHttpClient(@Value("${invoice.provider.base-url}") String baseUrl,
            @Value("${invoice.provider.connect-timeout}") Duration connectTimeout,
            @Value("${invoice.provider.read-timeout}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public void requestEnrichment(UUID correlationId, String requestJson) {
        HttpStatusCode status;
        try {
            status = restClient.post()
                    .uri(PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(CORRELATION_HEADER, correlationId.toString())
                    .body(requestJson)
                    .retrieve()
                    .toBodilessEntity()
                    .getStatusCode();
        } catch (HttpClientErrorException e) {
            int code = e.getStatusCode().value();
            // 408 e 429 são 4xx temporários (o provedor pede para tentar depois): retry, e não DLQ direto.
            if (code == 408 || code == 429) {
                throw new ProviderUnavailableException("Provedor pediu nova tentativa (status " + code + ")", e);
            }
            throw new ProviderRejectedException(code);
        } catch (HttpServerErrorException e) {
            throw new ProviderUnavailableException(
                    "Provedor indisponível (status " + e.getStatusCode().value() + ")", e);
        } catch (RestClientResponseException e) {
            throw new ProviderUnavailableException(
                    "Resposta inesperada do provedor (status " + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            throw new ProviderUnavailableException("Falha de comunicação com o provedor (timeout ou conexão recusada)", e);
        }
        // O RestClient só trata 4xx e 5xx como erro. Um 3xx (o redirecionamento não é seguido) chegaria aqui como se o
        // provedor tivesse aceitado, e a solicitação receberia ack sem resultado algum.
        if (!status.is2xxSuccessful()) {
            throw new ProviderUnavailableException("Resposta inesperada do provedor (status " + status.value() + ")", null);
        }
    }
}
