package dev.matheus.invoice.adapter.in.web.webhook.exception;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import dev.matheus.invoice.application.port.out.messaging.ResultPublishFailedException;
import lombok.extern.slf4j.Slf4j;

/**
 * Traduz erros do webhook para {@code application/problem+json} (RFC 9457), com título e detalhe em português.
 * Os logs não levam o segredo, a assinatura nem valores do corpo.
 */
@Slf4j
@Profile("webhook")
@RestControllerAdvice
class WebhookExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String RETRY_AFTER_SECONDS = "5";

    @ExceptionHandler(PayloadTooLargeException.class)
    ProblemDetail handlePayloadTooLarge(PayloadTooLargeException ex) {
        log.warn("Callback acima do limite de tamanho, nada foi publicado: motivo={}", ex.getMessage());
        return problem(HttpStatus.CONTENT_TOO_LARGE, "Corpo muito grande", ex.getMessage());
    }

    @ExceptionHandler(InvalidSignatureException.class)
    ProblemDetail handleInvalidSignature(InvalidSignatureException ex) {
        log.warn("Assinatura do callback inválida, nada foi publicado: motivo={}", ex.getMessage());
        return problem(HttpStatus.UNAUTHORIZED, "Assinatura inválida", ex.getMessage());
    }

    @ExceptionHandler(MalformedResultException.class)
    ProblemDetail handleMalformed(MalformedResultException ex) {
        log.warn("Callback malformado, nada foi publicado: motivo={}", ex.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "Resultado malformado", ex.getMessage());
    }

    @ExceptionHandler(ResultPublishFailedException.class)
    ResponseEntity<ProblemDetail> handlePublishFailed(ResultPublishFailedException ex) {
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "Publicação indisponível",
                "Não foi possível publicar o resultado. Reenvie o callback mais tarde");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Erro inesperado ao processar o callback", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno",
                "Não foi possível processar o callback. Tente novamente mais tarde");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
