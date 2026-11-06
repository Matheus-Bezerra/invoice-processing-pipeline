package dev.matheus.invoice.adapter.in.web.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import dev.matheus.invoice.application.service.CreateInvoiceResult;
import dev.matheus.invoice.application.service.InvoiceService;
import dev.matheus.invoice.domain.AccessKey;
import dev.matheus.invoice.domain.Invoice;
import dev.matheus.invoice.domain.exception.InvoiceNotFoundException;

@WebMvcTest(InvoiceController.class)
@ActiveProfiles("api")
class InvoiceControllerTest {

    private static final String KEY = "35260112345678000199550010000012341000012345";
    private static final AccessKey ACCESS_KEY = AccessKey.of(KEY);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InvoiceService service;

    private static String body(String key) {
        return "{\"accessKey\":\"" + key + "\"}";
    }

    @Test
    void t1_newKeyReturns201WithLocationAndInvoiceInAnalysis() throws Exception {
        Invoice invoice = Invoice.create(ACCESS_KEY, Instant.parse("2026-11-04T12:00:00Z"));
        when(service.create(ACCESS_KEY)).thenReturn(new CreateInvoiceResult(invoice, true));

        mockMvc.perform(post("/invoices").contentType(MediaType.APPLICATION_JSON).content(body(KEY)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/invoices/" + invoice.id()))
                .andExpect(jsonPath("$.id").value(invoice.id().toString()))
                .andExpect(jsonPath("$.accessKey").value(KEY))
                .andExpect(jsonPath("$.status").value("IN_ANALYSIS"))
                .andExpect(jsonPath("$.approvedAt").value(nullValue()))
                .andExpect(jsonPath("$.rejectionReason").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").value("2026-11-04T12:00:00Z"));
    }

    @Test
    void e7_repeatedKeyReturns200WithTheSameInvoice() throws Exception {
        Invoice invoice = Invoice.create(ACCESS_KEY, Instant.parse("2026-11-04T12:00:00Z"));
        when(service.create(ACCESS_KEY)).thenReturn(new CreateInvoiceResult(invoice, false));

        mockMvc.perform(post("/invoices").contentType(MediaType.APPLICATION_JSON).content(body(KEY)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.id").value(invoice.id().toString()));
    }

    @Test
    void e6_keyWithSurroundingSpacesIsTreatedAsTheSameKey() throws Exception {
        Invoice invoice = Invoice.create(ACCESS_KEY, Instant.parse("2026-11-04T12:00:00Z"));
        when(service.create(ACCESS_KEY)).thenReturn(new CreateInvoiceResult(invoice, true));

        mockMvc.perform(post("/invoices").contentType(MediaType.APPLICATION_JSON).content(body("  " + KEY + "  ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessKey").value(KEY));

        verify(service).create(ACCESS_KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "123",
            "3526011234567800019955001000001234100001234",
            "352601123456780001995500100000123410000123456",
            "3526011234567800019955001000001234100001234A",
            "3526.0112345678000199550010000012341000012345",
            "35260112-345678000199550010000012341000012345",
            "3526011234567800019955001000001234100001234á"})
    void t10_invalidKeyReturns400(String invalidKey) throws Exception {
        mockMvc.perform(post("/invoices").contentType(MediaType.APPLICATION_JSON).content(body(invalidKey)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Chave de acesso inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("A chave de acesso deve conter exatamente 44 dígitos"));

        verifyNoInteractions(service);
    }

    @Test
    void t10_missingOrNullKeyReturns400() throws Exception {
        mockMvc.perform(post("/invoices").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Chave de acesso inválida"));
        mockMvc.perform(post("/invoices").contentType(MediaType.APPLICATION_JSON).content("{\"accessKey\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Chave de acesso inválida"));

        verifyNoInteractions(service);
    }

    @Test
    void malformedJsonReturns400InPortuguese() throws Exception {
        mockMvc.perform(post("/invoices").contentType(MediaType.APPLICATION_JSON).content("{\"accessKey\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Corpo da requisição inválido"));
    }

    @Test
    void missingBodyReturns400InPortuguese() throws Exception {
        mockMvc.perform(post("/invoices").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Corpo da requisição inválido"));
    }

    @Test
    void getByIdReturns200() throws Exception {
        Invoice invoice = Invoice.create(ACCESS_KEY, Instant.parse("2026-11-04T12:00:00Z"));
        when(service.getById(invoice.id())).thenReturn(invoice);

        mockMvc.perform(get("/invoices/{id}", invoice.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(invoice.id().toString()))
                .andExpect(jsonPath("$.status").value("IN_ANALYSIS"));
    }

    @Test
    void getByIdReturns404WhenMissing() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getById(id)).thenThrow(new InvoiceNotFoundException());

        mockMvc.perform(get("/invoices/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Nota não encontrada"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void getByIdReturns400WhenIdIsNotAUuid() throws Exception {
        mockMvc.perform(get("/invoices/{id}", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.title").value("Parâmetro inválido"));
    }

    @Test
    void getByAccessKeyReturns200AndTrimsTheKey() throws Exception {
        Invoice invoice = Invoice.create(ACCESS_KEY, Instant.parse("2026-11-04T12:00:00Z"));
        when(service.getByAccessKey(ACCESS_KEY)).thenReturn(invoice);

        mockMvc.perform(get("/invoices").param("accessKey", " " + KEY + " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessKey").value(KEY));
    }

    @Test
    void getByAccessKeyReturns404WhenMissing() throws Exception {
        when(service.getByAccessKey(ACCESS_KEY)).thenThrow(new InvoiceNotFoundException());

        mockMvc.perform(get("/invoices").param("accessKey", KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Nota não encontrada"));
    }

    @Test
    void getByAccessKeyReturns400WhenInvalid() throws Exception {
        mockMvc.perform(get("/invoices").param("accessKey", "123"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Chave de acesso inválida"));

        verifyNoInteractions(service);
    }

    @Test
    void getByAccessKeyReturns400WhenParameterIsMissing() throws Exception {
        mockMvc.perform(get("/invoices"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Parâmetro obrigatório ausente"));
    }
}
