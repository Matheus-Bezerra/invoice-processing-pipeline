# Invoice Processing Pipeline — Fluxo e desenho

Este documento descreve o funcionamento do serviço de ponta a ponta. Ele é a referência para a implementação e será linkado no README.

Serviços (Docker Compose): `api`, `worker`, `invoice-webhook`, `provider-simulator`, `rabbitmq` (broker) e `postgres`.
`api`, `worker` e `invoice-webhook` compartilham o mesmo código e a mesma imagem, e sobem com perfis diferentes do Spring (`api`, `worker` e `webhook`). O `provider-simulator` é um projeto à parte, com imagem própria, e fala só HTTP.

## 1. Visão geral

```mermaid
flowchart LR
    client([Cliente])

    subgraph api_svc["api (perfil api)"]
        controller["POST e GET /invoices"]
        immediate["Publicação imediata após o commit (melhor esforço)"]
    end

    subgraph worker_svc["worker (perfil worker)"]
        relay["Relay do outbox (tick a cada 5s)"]
        reqconsumer["Consumer de solicitação (chama o provedor)"]
        consumer["Consumer de resultado"]
    end

    subgraph webhook_svc["invoice-webhook (perfil webhook, sem banco)"]
        webhook["POST /webhooks/enrichment-results (valida HMAC e payload)"]
    end

    subgraph broker["rabbitmq (broker)"]
        reqq[["invoice.enrichment.request"]]
        reqdlq[["invoice.enrichment.request.dlq"]]
        resq[["invoice.enrichment.result"]]
        dlq[["invoice.enrichment.result.dlq"]]
    end

    db[("postgres: invoices, outbox, processed_messages")]
    provider["provider-simulator (provedor externo fake, só HTTP)"]

    client -->|"HTTP"| controller
    controller -->|"uma transação: nota + linha do outbox"| db
    controller --> immediate
    immediate -->|"publica a solicitação"| reqq
    relay -->|"reserva linhas pendentes (SKIP LOCKED)"| db
    relay -->|"publica a solicitação"| reqq
    reqq --> reqconsumer
    reqconsumer -->|"HTTP POST /enrichment-requests, resposta 202"| provider
    reqconsumer -.->|"malformada, 4xx ou tentativas esgotadas"| reqdlq
    provider -->|"HTTP POST assinado (X-Signature)"| webhook
    webhook -->|"publica o resultado (publisher confirm)"| resq
    resq --> consumer
    consumer -->|"uma transação: atualiza a nota + processed_messages"| db
    consumer -.->|"malformada, chave inexistente ou tentativas esgotadas"| dlq
```

## 2. Fluxo feliz

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant API as api
    participant DB as PostgreSQL
    participant MQ as RabbitMQ
    participant W as worker
    participant P as provider-simulator
    participant H as invoice-webhook

    C->>API: POST /invoices {accessKey}
    API->>API: aplica trim e valida os 44 dígitos
    API->>DB: BEGIN, INSERT da nota (IN_ANALYSIS), INSERT no outbox, COMMIT
    API->>DB: reserva a linha do outbox (FOR UPDATE SKIP LOCKED)
    API->>MQ: publica EnrichmentRequest (messageId, invoiceId, accessKey)
    MQ-->>API: publisher confirm
    API->>DB: DELETE da linha do outbox, COMMIT
    API-->>C: 201 Created (status IN_ANALYSIS, sem approvedAt)

    MQ->>W: entrega a solicitação (consumer de solicitação)
    W->>P: POST /enrichment-requests (EnrichmentRequest)
    P-->>W: 202 Accepted (sem dados)
    W->>MQ: ack
    P->>P: pequeno atraso, monta resultado completo e assina o corpo (HMAC-SHA256)
    P->>H: POST /webhooks/enrichment-results (EnrichmentResult, X-Signature)
    H->>H: confere a assinatura e valida o payload
    H->>MQ: publica EnrichmentResult (messageId derivado do da solicitação, accessKey, cnpj, amount, issueDate, cancelled=false)
    MQ-->>H: publisher confirm
    H-->>P: 202 Accepted
    MQ->>W: entrega o resultado (consumer de resultado)
    W->>DB: BEGIN, trava a nota, INSERT em processed_messages, aprova, COMMIT
    W->>MQ: ack

    C->>API: GET /invoices/{id}
    API-->>C: 200 (status APPROVED, dados enriquecidos, approvedAt)
```

A publicação imediata é disparada no `afterCommit` da transação da nota, em outra thread e numa transação nova, então o `POST` não espera o broker. Se ela falhar, o cliente recebe `201` do mesmo jeito e a nota continua `IN_ANALYSIS`. A linha do outbox permanece para o relay (seção 3).
O request do cliente nunca espera o provedor, e a nota nunca é aprovada durante a criação. A chamada HTTP ao provedor é do `worker`, feita depois e de forma assíncrona.

### 2.1 As duas chamadas HTTP com o provedor

| Chamada | Quem chama | Corpo | Resposta |
|---|---|---|---|
| `POST {PROVIDER_BASE_URL}/enrichment-requests` | `worker` | `EnrichmentRequest` | `202` sem dados. `4xx` é erro permanente, `5xx` e timeout são erro técnico |
| `POST /webhooks/enrichment-results` | `provider-simulator` | `EnrichmentResult`, com `X-Signature: sha256=<hex>` | `202` publicado, `401` assinatura inválida, `400` payload malformado, `503` falha ao publicar |

Os dados da nota (cnpj, valor, data, cancelamento) trafegam **só** no callback. A URL do callback é configuração do provedor e não vai na solicitação.

## 3. Falha de publicação e recuperação

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant API as api
    participant DB as PostgreSQL
    participant MQ as RabbitMQ
    participant W as worker (relay)

    C->>API: POST /invoices {accessKey}
    API->>DB: COMMIT da nota (IN_ANALYSIS) + linha do outbox
    API-xMQ: publicação falha (broker fora do ar)
    API-->>C: 201 Created (nota preservada, solicitação ainda pendente no outbox)

    loop a cada tick (5s), com backoff por linha
        W->>DB: reserva linhas com next_attempt_at <= now (SKIP LOCKED)
        W-xMQ: publicação falha de novo
        W->>DB: attempts + 1, next_attempt_at = now + min(2^attempts s, 60s), last_error
    end

    Note over MQ: o broker voltou
    W->>DB: reserva a linha
    W->>MQ: publica EnrichmentRequest (mesmo messageId)
    MQ-->>W: publisher confirm
    W->>DB: DELETE da linha do outbox, COMMIT
```

O relay nunca desiste de uma linha, porque a solicitação não pode ficar esquecida. O backoff tem teto de 60s.

## 4. Decisão do consumer de resultado

```mermaid
flowchart TD
    A["Mensagem de resultado recebida"] --> B{"JSON válido e campos obrigatórios (messageId, accessKey)?"}
    B -- "não (malformada)" --> DLQ[["DLQ, sem retry"]]
    B -- "sim" --> C["BEGIN da transação"]
    C --> D{"Existe nota com essa accessKey?"}
    D -- "não" --> DLQ
    D -- "sim" --> E["Trava a linha da nota"]
    E --> F{"messageId já processado?"}
    F -- "sim" --> ACK1["ack e log de duplicata, sem alterações"]
    F -- "não" --> G["INSERT em processed_messages"]
    G --> H{"Status é IN_ANALYSIS?"}
    H -- "não" --> ACK2["ack e log de nota já finalizada, sem alterações"]
    H -- "sim" --> I{"cancelled = true?"}
    I -- "sim" --> R1["REJECTED: nota cancelada pelo emitente"]
    I -- "não" --> J{"cnpj, amount, issueDate e cancelled presentes?"}
    J -- "não" --> R2["REJECTED: incompleta, lista os campos ausentes"]
    J -- "sim" --> K{"CNPJ válido (14 caracteres, dígitos verificadores) e amount não negativo?"}
    K -- "não" --> R3["REJECTED: dados inválidos"]
    K -- "sim" --> AP["APPROVED: grava os dados e approvedAt"]
    R1 --> CM["COMMIT e ack"]
    R2 --> CM
    R3 --> CM
    AP --> CM

    TX["Erro técnico dentro da transação (por exemplo, banco fora do ar)"] --> RB["Rollback"]
    RB --> RT{"Já usou os 3 retries?"}
    RT -- "não" --> WT["Espera 2s, 4s ou 8s (cresce a cada retry) e processa de novo"]
    WT --> A
    RT -- "sim" --> DLQ
```

Só o erro técnico é reprocessado. Mensagem malformada e chave inexistente vão direto para a DLQ, porque tentar de novo não resolveria.
Uma nota rejeitada mantém `approvedAt` vazio e grava um `rejectionReason` legível.

### 4.1 Decisão do webhook

```mermaid
flowchart TD
    A["POST /webhooks/enrichment-results recebido"] --> B{"X-Signature presente e igual ao HMAC-SHA256 do corpo bruto?"}
    B -- "não" --> R401["401 Unauthorized, nada é publicado"]
    B -- "sim" --> C{"JSON válido, com messageId e payload.accessKey (44 dígitos), e campos legíveis?"}
    C -- "não" --> R400["400 Bad Request, nada é publicado"]
    C -- "sim" --> D["Publica EnrichmentResult em invoice.enrichment, routing key result"]
    D --> E{"ack do broker e sem devolução?"}
    E -- "sim" --> R202["202 Accepted"]
    E -- "não (nack, timeout ou devolvida)" --> R503["503 Service Unavailable, o provedor reenvia"]
```

O webhook valida só a assinatura e o formato. Cancelamento, CNPJ e valor são decididos pelo consumer de resultado (diagrama acima). Chave inexistente também só aparece lá, porque o webhook não acessa o banco.

### 4.2 Decisão do consumer de solicitação

```mermaid
flowchart TD
    A["Solicitação recebida"] --> B{"JSON válido, com messageId, invoiceId e accessKey?"}
    B -- "não (malformada)" --> DLQ[["DLQ da solicitação, sem retry"]]
    B -- "sim" --> C["POST /enrichment-requests ao provedor"]
    C --> D{"Resposta"}
    D -- "202" --> ACK["ack"]
    D -- "4xx (erro permanente)" --> DLQ
    D -- "5xx, timeout ou conexão recusada" --> RT{"Já usou os 3 retries?"}
    RT -- "não" --> WT["Espera 2s, 4s ou 8s e chama de novo"]
    WT --> C
    RT -- "sim" --> DLQ
```

Como no consumer de resultado, só o erro técnico é reprocessado. O provedor deduplica pelo `messageId`, então um retry depois de um timeout não gera dois resultados.

## 5. Mensagem duplicada e reentrega

```mermaid
sequenceDiagram
    autonumber
    participant MQ as RabbitMQ
    participant W as worker (consumer)
    participant DB as PostgreSQL

    MQ->>W: entrega o resultado (messageId M1)
    W->>DB: BEGIN, trava a nota, INSERT de M1 em processed_messages, aprova, COMMIT
    Note over W: o worker cai (ou o canal fecha) antes do ack
    W--xMQ: o ack nunca é enviado
    Note over MQ: a mensagem continua sem ack, a conexão foi perdida
    MQ->>W: reentrega o resultado (messageId M1, redelivered = true)
    W->>DB: BEGIN, trava a nota, M1 já está em processed_messages
    W->>DB: ROLLBACK (sem alterações)
    W->>MQ: ack
    Note over DB: a nota não mudou (mesmo status, mesmo approvedAt)
    Note over MQ,DB: Um messageId diferente para uma nota finalizada também recebe ack e é ignorado pela guarda de estado
```

Duas camadas protegem contra duplicidade: `processed_messages` (mesmo `messageId`) e a guarda de estado final (qualquer resultado para uma nota finalizada). O lock da linha serializa entregas concorrentes.

## 6. Estados da nota

```mermaid
stateDiagram-v2
    [*] --> IN_ANALYSIS: POST /invoices
    IN_ANALYSIS --> APPROVED: completa, válida e não cancelada
    IN_ANALYSIS --> REJECTED: cancelada, incompleta ou dados inválidos
    APPROVED --> [*]
    REJECTED --> [*]
    note right of APPROVED: Estado final. Resultados posteriores não alteram
    note right of REJECTED: Estado final. Resultados posteriores não alteram
```

## 7. Modelo de dados

```mermaid
erDiagram
    INVOICES ||--o| OUTBOX : "solicitação pendente"

    INVOICES {
        uuid id PK
        char44 access_key UK
        string status "IN_ANALYSIS, APPROVED ou REJECTED"
        string cnpj "nullable"
        decimal amount "nullable"
        date issue_date "nullable"
        boolean cancelled "nullable"
        timestamptz approved_at "nullable"
        string rejection_reason "nullable"
        timestamptz created_at
        timestamptz updated_at
    }

    OUTBOX {
        uuid id PK "messageId da solicitação"
        uuid invoice_id FK
        char44 access_key
        jsonb payload
        int attempts
        timestamptz next_attempt_at
        string last_error "nullable"
        timestamptz created_at
    }

    PROCESSED_MESSAGES {
        uuid message_id PK "messageId do resultado"
        timestamptz processed_at
    }
```

Uma linha do outbox só existe enquanto a solicitação está pendente: ela é apagada na mesma transação que confirma a publicação.

## 8. Decisões refletidas aqui

- Relay agendado do outbox mais publicação imediata após o commit. Os dois usam a mesma função e `FOR UPDATE SKIP LOCKED`.
- A entrega é at-least-once. Uma queda entre a publicação e o commit pode publicar a solicitação duas vezes, então o provedor deve deduplicar pelo `messageId` da solicitação.
- Publicação imediata no `afterCommit` da transação que gravou a nota, em outra thread (`REQUIRES_NEW`), com falha só registrada em log.
- *Publisher confirm* correlacionado com `mandatory`: só apaga a linha com `ack` e sem devolução.
- Relay: tick de 5s, backoff exponencial (2s, 4s, 8s, 16s, 32s) com teto de 60s e sem limite de tentativas.
- Consumer: 3 retries dentro do worker, com esperas de 2s, 4s e 8s (até 4 execuções no total), e depois DLQ. Não há tabela de log de erros, a DLQ guarda a mensagem.
- O provedor fala HTTP, não fila. O `worker` consome a solicitação e a repassa por `POST /enrichment-requests` (resposta `202` sem dados). O resultado volta por callback no webhook, e os dados da nota trafegam só nele.
- O `invoice-webhook` é um perfil (`webhook`) da mesma imagem de `api` e `worker`, sem banco. Valida o HMAC-SHA256 do corpo bruto e o formato, e publica o resultado na fila. Assinatura inválida: `401`. Payload malformado: `400`, sem publicar. Falha ao publicar: `503`, e o provedor reenvia.
- A solicitação tem DLQ própria (`invoice.enrichment.request.dlq`) e a mesma política de retry do resultado: `4xx` e malformada vão direto à DLQ, `5xx` e timeout fazem 3 retries.
- O `provider-simulator` é um projeto à parte, só HTTP: recebe a chamada, responde `202`, espera, assina o resultado e chama o webhook (com até 3 reenvios em `5xx`). A URL do callback é configuração dele, nunca vem na solicitação.
- O `messageId` do resultado é derivado do `messageId` da solicitação (feito pelo simulador). Uma solicitação entregue duas vezes, ou um callback reenviado, gera o mesmo resultado, que `processed_messages` descarta.
- Horários em UTC (`timestamptz` no banco, ISO-8601 na API).
- Criação duplicada (mesma chave de acesso): `200` com a nota existente. A garantia vem da constraint `UNIQUE`, e não de um `SELECT` prévio.
- Chave de acesso: passa por `trim` e precisa ter exatamente 44 dígitos. Fora disso, `400`.
- Idempotência dos resultados: `processed_messages` gravada na mesma transação do update, mais a guarda de estado final.
- Validação do resultado: CNPJ válido e valor não negativo. O CNPJ pode ser alfanumérico (14 caracteres, sem máscara, 12 primeiros em `A-Z` ou dígitos e 2 dígitos verificadores). Se falhar, a nota é rejeitada.

## 9. Limitações conhecidas

- A espera do retry acontece dentro do worker e bloqueia aquele consumer (`prefetch = 1`). Filas de retry com TTL evitariam isso.
- O relay mantém uma transação aberta no banco enquanto publica. Um broker lento segura os locks das linhas durante esse tempo.
- A publicação imediata usa um executor limitado. Se a fila dele encher, o relay publica as solicitações excedentes (até 5s depois).
- Apagar as linhas do outbox após a publicação não deixa histórico de publicação.
- O reprocessamento das DLQs (solicitação e resultado) é manual.
- O HMAC não tem timestamp nem nonce, então um callback válido capturado pode ser reenviado. A idempotência do consumer limita o efeito.
- O segredo do HMAC é simétrico e compartilhado, sem rotação automática.
- Se o callback nunca chegar, ou a solicitação cair na DLQ, a nota fica em `IN_ANALYSIS` sem timeout.
