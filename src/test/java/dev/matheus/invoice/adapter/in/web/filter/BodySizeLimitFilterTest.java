package dev.matheus.invoice.adapter.in.web.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.ServletInputStream;

/** Teto do corpo das requisições, usado pela {@code api} e pelo webhook, cada um com o seu limite. */
class BodySizeLimitFilterTest {

    private static final int LIMIT = 16;

    private final BodySizeLimitFilter filter = new BodySizeLimitFilter(LIMIT);

    private static byte[] bytes(int size) {
        return "x".repeat(size).getBytes(StandardCharsets.UTF_8);
    }

    private static MockHttpServletRequest post(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/qualquer");
        request.setContent(body);
        return request;
    }

    @Test
    void bodyAtTheLimitReachesTheNextStepIntact() throws Exception {
        MockHttpServletRequest request = post(bytes(LIMIT));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
        assertThat(chain.getRequest().getInputStream().readAllBytes()).isEqualTo(bytes(LIMIT));
    }

    @Test
    void declaredLengthAboveTheLimitIs413WithoutReadingTheBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/qualquer") {
            @Override
            public ServletInputStream getInputStream() {
                throw new AssertionError("o corpo não deveria ser lido");
            }
        };
        request.setContent(bytes(LIMIT + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void chunkedBodyAboveTheLimitIs413() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/qualquer") {
            @Override
            public long getContentLengthLong() {
                return -1; // sem Content-Length, como no chunked
            }
        };
        request.setContent(bytes(LIMIT + 1));
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void tooLargeAnswersWithProblemJsonInPortuguese() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(post(bytes(LIMIT + 1)), response, new MockFilterChain());

        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"title\":\"Corpo muito grande\"")
                .contains("\"status\":413")
                .contains("passa do limite de " + LIMIT + " bytes");
    }

    @Test
    void requestsWithoutBodyAreNotTouched() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/qualquer");
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void limitMustBePositive() {
        assertThatThrownBy(() -> new BodySizeLimitFilter(0)).isInstanceOf(IllegalStateException.class);
    }
}
