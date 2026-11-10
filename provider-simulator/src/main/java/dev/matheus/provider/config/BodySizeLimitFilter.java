package dev.matheus.provider.config;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * Limita o tamanho do corpo das requisições do simulador antes de o Spring ler o corpo, para que um corpo
 * enorme não esgote a memória. O Tomcat só limita corpos de formulário, não {@code application/json}.
 *
 * <p>Com {@code Content-Length} acima do teto, responde {@code 413} sem ler nada. Sem {@code Content-Length}
 * (<i>chunked</i>), lê no máximo {@code teto + 1} bytes e devolve o corpo lido ao resto da cadeia.
 */
@Slf4j
@Component
public class BodySizeLimitFilter extends OncePerRequestFilter {

    private static final Set<String> METHODS_WITH_BODY = Set.of("POST", "PUT", "PATCH");

    private final int maxBytes;

    public BodySizeLimitFilter(@Value("${simulator.max-body-bytes}") int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalStateException("simulator.max-body-bytes precisa ser maior que zero");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !METHODS_WITH_BODY.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            tooLarge(response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) {
            tooLarge(response);
            return;
        }
        chain.doFilter(new BufferedBodyRequest(request, body), response);
    }

    private void tooLarge(HttpServletResponse response) throws IOException {
        log.warn("Requisição acima do limite de tamanho, recusada: limite={} bytes", maxBytes);
        response.setStatus(413);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(("{\"type\":\"about:blank\",\"title\":\"Corpo muito grande\",\"status\":413,"
                + "\"detail\":\"O corpo da requisição passa do limite de %d bytes\"}").formatted(maxBytes));
    }

    /** Entrega ao resto da cadeia o corpo que o filtro já leu. */
    private static final class BufferedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        BufferedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return source.read();
                }

                @Override
                public int read(byte[] target, int offset, int length) {
                    return source.read(target, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Leitura assíncrona não é usada neste simulador");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
