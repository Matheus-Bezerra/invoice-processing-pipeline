package dev.matheus.invoice.adapter.in.web.exception;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import dev.matheus.invoice.domain.exception.InvalidAccessKeyException;
import dev.matheus.invoice.domain.exception.InvoiceNotFoundException;
import lombok.extern.slf4j.Slf4j;

/** Traduz erros para {@code application/problem+json} (RFC 9457), com título e detalhe em português. */
@Slf4j
@Profile("api")
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidAccessKeyException.class)
    ProblemDetail handleInvalidAccessKey(InvalidAccessKeyException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Chave de acesso inválida", ex.getMessage());
    }

    @ExceptionHandler(InvoiceNotFoundException.class)
    ProblemDetail handleNotFound(InvoiceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Nota não encontrada", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Erro inesperado ao processar a requisição", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno",
                "Não foi possível processar a requisição. Tente novamente mais tarde");
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(problem(HttpStatus.BAD_REQUEST, "Corpo da requisição inválido",
                "O corpo da requisição está ausente ou não é um JSON válido"), headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return respond(problem(HttpStatus.BAD_REQUEST, "Parâmetro inválido",
                "O valor informado para o parâmetro '" + ex.getPropertyName() + "' tem formato inválido"),
                headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return respond(problem(HttpStatus.BAD_REQUEST, "Parâmetro obrigatório ausente",
                "O parâmetro '" + ex.getParameterName() + "' é obrigatório"), headers, request);
    }

    private ResponseEntity<Object> respond(ProblemDetail problem, HttpHeaders headers, WebRequest request) {
        return handleExceptionInternal(new IllegalStateException(), problem, headers,
                HttpStatusCode.valueOf(problem.getStatus()), request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
