package dev.matheus.invoice.adapter.in.web.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import dev.matheus.invoice.adapter.in.web.api.InvoiceApi;
import dev.matheus.invoice.adapter.in.web.dto.CreateInvoiceRequest;
import dev.matheus.invoice.adapter.in.web.dto.InvoiceResponse;
import dev.matheus.invoice.application.service.invoice.CreateInvoiceResult;
import dev.matheus.invoice.application.service.invoice.InvoiceService;
import dev.matheus.invoice.domain.AccessKey;
import lombok.RequiredArgsConstructor;

@RestController
@Profile("api")
@RequiredArgsConstructor
public class InvoiceController implements InvoiceApi {

    private final InvoiceService service;

    @Override
    public ResponseEntity<InvoiceResponse> create(CreateInvoiceRequest request) {
        CreateInvoiceResult result = service.create(AccessKey.of(request.accessKey()));
        InvoiceResponse body = InvoiceResponse.from(result.invoice());
        if (result.created()) {
            return ResponseEntity.created(URI.create("/invoices/" + body.id())).body(body);
        }
        return ResponseEntity.ok(body);
    }

    @Override
    public InvoiceResponse getById(UUID id) {
        return InvoiceResponse.from(service.getById(id));
    }

    @Override
    public InvoiceResponse getByAccessKey(String accessKey) {
        return InvoiceResponse.from(service.getByAccessKey(AccessKey.of(accessKey)));
    }
}
