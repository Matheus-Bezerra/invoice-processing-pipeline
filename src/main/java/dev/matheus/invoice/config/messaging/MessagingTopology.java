package dev.matheus.invoice.config.messaging;

/** Nomes da topologia do RabbitMQ (spec, seção 3.3). */
public final class MessagingTopology {

    public static final String EXCHANGE = "invoice.enrichment";
    public static final String REQUEST_QUEUE = "invoice.enrichment.request";
    public static final String REQUEST_ROUTING_KEY = "request";
    public static final String REQUEST_DLQ = "invoice.enrichment.request.dlq";
    public static final String REQUEST_DLQ_ROUTING_KEY = "request.dlq";
    public static final String RESULT_QUEUE = "invoice.enrichment.result";
    public static final String RESULT_ROUTING_KEY = "result";
    public static final String DLX = "invoice.enrichment.dlx";
    public static final String RESULT_DLQ = "invoice.enrichment.result.dlq";
    public static final String RESULT_DLQ_ROUTING_KEY = "result.dlq";

    private MessagingTopology() {
    }
}
