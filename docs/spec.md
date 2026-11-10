# Invoice Processing Pipeline — Especificação

Este documento é o contrato para a implementação e para os testes. O desenho do fluxo está em [flow.md](flow.md). Regras de negócio e decisões estão no [DESAFIO.md](../DESAFIO.md).

Convenções: texto em português, identificadores técnicos (classes, campos, estados, filas, rotas) em inglês. Toda mensagem voltada a pessoas, de sucesso ou de erro, em português: motivos de rejeição, erros e validações da API, textos do Swagger (inclusive as descrições das respostas de sucesso) e logs. Horários em UTC, no formato ISO-8601.

## 1. Escopo

O cliente envia a chave de acesso de uma nota fiscal. O serviço persiste a nota em `IN_ANALYSIS`, publica uma solicitação de enriquecimento por mensageria (outbox) e, ao receber o resultado do provedor, aprova ou rejeita a nota.

O provedor externo **não** fala por fila. O `worker` consome a solicitação e a repassa por HTTP. O provedor responde `202` e, depois, devolve o resultado chamando um **webhook** assinado (HMAC). O serviço `invoice-webhook` valida a chamada e publica o resultado na fila, de onde o `worker` o consome.

| Serviço | Papel |
|---|---|
| `api` | HTTP: cadastra e consulta notas. Grava nota + outbox e tenta publicar logo após o commit |
| `worker` | Relay do outbox (agendado), consumer da solicitação (chama o provedor por HTTP) e consumer do resultado |
| `invoice-webhook` | Recebe o callback assinado do provedor, valida e publica o resultado na fila. Tem Swagger próprio e não usa banco |
| `provider-simulator` | Simula o provedor externo: recebe a chamada HTTP, espera e chama o webhook com o corpo assinado |
| `rabbitmq` | Broker |
| `postgres` | Banco |

`api`, `worker` e `invoice-webhook` são o mesmo código e a mesma imagem, ativados pelos perfis `api`, `worker` e `webhook` do Spring. O `provider-simulator` é um projeto Maven à parte, com imagem própria, porque representa um sistema externo.

Adaptação do enunciado: o `DESAFIO.md` descreve "o provedor publica o resultado". Aqui o provedor chama um webhook e quem publica na fila é o `invoice-webhook`, em nome dele. O restante do fluxo e o contrato do resultado (3.2) seguem o enunciado. A chamada HTTP do `worker` ao provedor acontece de forma assíncrona, depois do request do cliente, então o request continua sem consultar o provedor nem aguardar o enriquecimento.

## 2. Contrato HTTP

Formato de erro: `application/problem+json` (RFC 9457), com `title`, `status` e `detail`. `title` e `detail` em português, por exemplo `title: "Chave de acesso inválida"` e `detail: "A chave de acesso deve conter exatamente 44 dígitos"`. As respostas de sucesso devolvem o próprio recurso, sem texto de mensagem.

### 2.1 `POST /invoices`

Corpo:

```json
{ "accessKey": "35260112345678000199550010000012341000012345" }
```

A chave passa por `trim` e precisa ter **exatamente 44 dígitos** (`^[0-9]{44}$`). Pontos, hífens, letras e acentos são inválidos. A chave já limpa é a que vai para o banco.

| Situação | Resposta |
|---|---|
| Chave nova | `201 Created`, header `Location: /invoices/{id}`, nota em `IN_ANALYSIS` |
| Chave já cadastrada | `200 OK`, a nota existente no estado em que estiver |
| Chave ausente, vazia ou fora do formato | `400 Bad Request` |
| JSON malformado | `400 Bad Request` |

Garantias:

- A unicidade vem da constraint `UNIQUE` em `access_key`, e não de um `SELECT` prévio. A criação usa `INSERT ... ON CONFLICT DO NOTHING` e relê a nota quando houver conflito.
- Uma chave repetida **não** cria nova linha no outbox.
- A nota nunca é aprovada durante o request, e o request não consulta o provedor.
- Se a publicação imediata falhar, a resposta continua `201`.

### 2.2 `GET /invoices/{id}`

| Situação | Resposta |
|---|---|
| Existe | `200 OK` |
| Não existe | `404 Not Found` |
| `id` que não é UUID | `400 Bad Request` |

### 2.3 `GET /invoices?accessKey={accessKey}`

A chave também passa por `trim` e pela mesma validação.

| Situação | Resposta |
|---|---|
| Existe | `200 OK` |
| Não existe | `404 Not Found` |
| Chave fora do formato | `400 Bad Request` |

### 2.4 `InvoiceResponse`

```json
{
  "id": "0b6f5c1e-8d2a-4c53-9a43-6f4b6a0f2f11",
  "accessKey": "35260112345678000199550010000012341000012345",
  "status": "APPROVED",
  "cnpj": "12ABC34501DE35",
  "amount": 1500.00,
  "issueDate": "2026-01-10",
  "cancelled": false,
  "approvedAt": "2026-11-04T12:00:03Z",
  "rejectionReason": null,
  "createdAt": "2026-11-04T12:00:00Z"
}
```

| Campo | Tipo | Observação |
|---|---|---|
| `status` | `IN_ANALYSIS`, `APPROVED`, `REJECTED` | |
| `cnpj`, `amount`, `issueDate`, `cancelled` | nulos até o resultado chegar | Também são gravados em notas rejeitadas, quando vierem |
| `approvedAt` | nulo se não aprovada | Horário do processamento no servidor, em UTC |
| `rejectionReason` | nulo se não rejeitada | Texto legível (ver 4.4) |

### 2.5 Webhook do provedor (`invoice-webhook`)

Serviço separado do contrato do cliente, com Swagger UI próprio (textos em português). Só o provedor chama este endpoint.

`POST /webhooks/enrichment-results`

Cabeçalhos:

| Cabeçalho | Obrigatório | Descrição |
|---|---|---|
| `Content-Type` | sim | `application/json` |
| `X-Signature` | sim | `sha256=<hex>`: HMAC-SHA256 do **corpo bruto** (bytes recebidos) com o segredo `WEBHOOK_HMAC_SECRET` |

Corpo: o envelope `EnrichmentResult` de 3.2, sem alterações. O `messageId` já vem derivado do `messageId` da solicitação (ver 3.2), então reenvios do mesmo callback mantêm o mesmo `messageId`.

Ordem de verificação: assinatura, depois payload, depois publicação.

| Situação | Resposta |
|---|---|
| Resultado válido, publicado e confirmado pelo broker | `202 Accepted`, sem corpo |
| `X-Signature` ausente, mal formada ou que não confere com o corpo | `401 Unauthorized`, nada é publicado |
| JSON malformado, `messageId` ou `payload.accessKey` ausentes, ou campo com tipo ou formato ilegível (mesmas regras de 3.2) | `400 Bad Request`, nada é publicado |
| Broker indisponível, `nack`, timeout ou mensagem devolvida (*publisher confirm*) | `503 Service Unavailable`. O provedor deve reenviar |

Garantias:

- A assinatura é comparada em tempo constante e sobre os bytes exatos recebidos, antes de qualquer parse do JSON.
- A `payload.accessKey` também é conferida no formato (44 dígitos), a mesma regra do consumer de resultado: chave fora do formato recebe `400` já no webhook.
- O webhook **não valida regra de negócio** (CNPJ, valor, cancelamento): só o formato da mensagem. A decisão de aprovar ou rejeitar continua no consumer do resultado.
- Um mesmo callback recebido duas vezes é publicado duas vezes. O consumer descarta a duplicata por `processed_messages`.
- O webhook não acessa o banco e não sabe se a chave existe. Chave inexistente continua indo para a DLQ do resultado (4.5).
- Erros em `application/problem+json` (RFC 9457), com `title` e `detail` em português. Logs sem o segredo, sem o valor da assinatura e sem CNPJ ou valor da nota.

## 3. Mensagens

Formato JSON, `contentType: application/json`, mensagens persistentes. Todas usam o mesmo envelope enxuto.

| Campo do envelope | Descrição |
|---|---|
| `messageId` | UUID. Identifica a mensagem e permanece o mesmo nas reentregas |
| `eventType` | Tipo do evento |
| `occurredAt` | Instante em que a mensagem foi gerada (ISO-8601, UTC) |
| `correlationId` | Para rastrear nos logs. Na solicitação é o `invoiceId`, e o provedor o copia para o resultado |
| `payload` | Conteúdo da mensagem |

### 3.1 `EnrichmentRequest`

Fila `invoice.enrichment.request`, `eventType: invoice.enrichment.requested`.

```json
{
  "messageId": "6a1f0c58-3f2e-4b8e-9d1b-0d6b6e2c9a10",
  "eventType": "invoice.enrichment.requested",
  "occurredAt": "2026-11-04T12:00:00Z",
  "correlationId": "0b6f5c1e-8d2a-4c53-9a43-6f4b6a0f2f11",
  "payload": {
    "invoiceId": "0b6f5c1e-8d2a-4c53-9a43-6f4b6a0f2f11",
    "accessKey": "35260112345678000199550010000012341000012345"
  }
}
```

- `messageId` é o `id` da linha do outbox. Ele não muda entre republicações.
- O envio é *at-least-once*, então o provedor pode receber a mesma solicitação mais de uma vez e deve deduplicar pelo `messageId`.

### 3.2 `EnrichmentResult`

Fila `invoice.enrichment.result`, `eventType: invoice.enrichment.completed`.

```json
{
  "messageId": "c3b2f2a1-5d6e-3f7a-8b9c-1e2d3c4b5a69",
  "eventType": "invoice.enrichment.completed",
  "occurredAt": "2026-11-04T12:00:02Z",
  "correlationId": "0b6f5c1e-8d2a-4c53-9a43-6f4b6a0f2f11",
  "payload": {
    "accessKey": "35260112345678000199550010000012341000012345",
    "cnpj": "12ABC34501DE35",
    "amount": 1500.00,
    "issueDate": "2026-01-10",
    "cancelled": false
  }
}
```

| Campo | Obrigatório | Observação |
|---|---|---|
| `messageId` | sim | UUID. Se ausente ou inválido, a mensagem é malformada |
| `payload.accessKey` | sim | A nota é localizada por ela. O resultado nunca cria nota |
| `payload.cnpj` | não | Ausente ou nulo indica resultado incompleto |
| `payload.amount` | não | Número decimal. Ausente ou nulo indica incompleto |
| `payload.issueDate` | não | `yyyy-MM-dd`. Ausente ou nulo indica incompleto |
| `payload.cancelled` | não no formato | Ausente ou nulo é tratado como incompleto e a nota é rejeitada |

Uma mensagem é **malformada** quando não é JSON válido, quando falta `messageId` ou `payload.accessKey`, ou quando um campo tem tipo ou formato ilegível (por exemplo `amount: "abc"` ou uma data impossível). Ela vai direto para a DLQ.

O `cancelled` precisa ser lido como `Boolean` e nunca como `boolean`. Com o tipo primitivo, um campo ausente viraria `false` e aprovaria a nota por engano.

No simulador, o `messageId` do resultado é derivado do `messageId` da solicitação (`UUID.nameUUIDFromBytes("result:" + requestMessageId)`). Assim uma solicitação entregue duas vezes gera o mesmo resultado nas duas. O resultado chega à fila pelo webhook (2.5): o provedor envia o envelope acima como corpo do callback, e o `invoice-webhook` o publica sem alterar o conteúdo.

### 3.3 Topologia do RabbitMQ

| Item | Nome | Observação |
|---|---|---|
| Exchange | `invoice.enrichment` | direct, durável |
| Fila de solicitação | `invoice.enrichment.request` | routing key `request`, com DLX configurado |
| Fila de resultado | `invoice.enrichment.result` | routing key `result`, com DLX configurado |
| Dead letter exchange | `invoice.enrichment.dlx` | direct |
| DLQ da solicitação | `invoice.enrichment.request.dlq` | routing key `request.dlq` |
| DLQ do resultado | `invoice.enrichment.result.dlq` | routing key `result.dlq` |

Filas duráveis, *publisher confirms* ligados e `prefetch = 1` nos dois consumers (solicitação e resultado). A fila de solicitação agora é consumida pelo `worker`, que chama o provedor por HTTP (3.4), então ela também tem DLQ.

### 3.4 Chamada ao provedor (HTTP)

O `worker` consome `invoice.enrichment.request` e repassa a solicitação ao provedor.

`POST {PROVIDER_BASE_URL}/enrichment-requests`

| Item | Valor |
|---|---|
| Corpo | O envelope `EnrichmentRequest` de 3.1, sem alterações |
| `Content-Type` | `application/json` |
| `X-Correlation-Id` | O `correlationId` da mensagem (o `invoiceId`) |

| Resposta do provedor | Significado | Destino da mensagem |
|---|---|---|
| `202 Accepted`, sem dados | Solicitação aceita. O resultado virá depois, pelo webhook | `ack` |
| `4xx` | Erro permanente (solicitação rejeitada pelo provedor) | DLQ direto, sem retry |
| `5xx`, timeout ou conexão recusada | Erro técnico | Retry (ver 4.8) e depois DLQ |

- O `202` **não** traz dados da nota. Os dados chegam só no callback (2.5).
- A URL do callback **não** vai na solicitação: é configuração do próprio provedor. Assim a mensagem não consegue apontar o provedor para um destino arbitrário.
- O envio é *at-least-once*: o provedor pode receber a mesma solicitação mais de uma vez e deduplica pelo `messageId`.
- Timeouts de conexão e de resposta curtos e configuráveis (8).

## 4. Regras de negócio

### 4.1 Estados

`IN_ANALYSIS` é o estado inicial. `APPROVED` e `REJECTED` são finais: um novo resultado para uma nota finalizada não altera dados nem decisão.

| Nome no desafio | Enum |
|---|---|
| `ANALISE` | `IN_ANALYSIS` |
| `APROVADA` | `APPROVED` |
| `REJEITADA` | `REJECTED` |

### 4.2 Decisão ao processar um resultado

A nota está em `IN_ANALYSIS` e segue esta ordem. A primeira regra que se aplicar decide:

1. `cancelled = true` → `REJECTED`, motivo de cancelamento.
2. Falta `cnpj`, `amount`, `issueDate` ou `cancelled` → `REJECTED`, motivo de incompleto.
3. CNPJ inválido ou `amount` negativo → `REJECTED`, motivo de dados inválidos.
4. Caso contrário → `APPROVED`, grava os dados e `approvedAt`.

Em toda rejeição `approvedAt` fica vazio. Um valor `0` é aceito.

### 4.3 Validação do CNPJ

Sem máscara, 14 caracteres. Os 12 primeiros são letras maiúsculas (`A-Z`) ou dígitos, e os 2 últimos são dígitos verificadores numéricos. Minúsculas são inválidas e não há normalização. Os dígitos verificadores seguem o cálculo oficial para CNPJ alfanumérico, conferido na documentação da Receita no momento da implementação.

### 4.4 Motivos de rejeição

Texto legível gravado em `rejection_reason`. Os nomes dos campos aparecem como no payload, sempre na ordem `cnpj`, `amount`, `issueDate`, `cancelled`.

| Caso | Texto |
|---|---|
| Cancelada | `Nota cancelada pelo emitente` |
| Incompleta | `Enriquecimento incompleto: campos ausentes: cnpj, amount` |
| Inválida | `Dados de enriquecimento inválidos: cnpj inválido, amount negativo` |

### 4.5 Destino das mensagens de resultado

| Situação | Retry | Destino |
|---|---|---|
| Mensagem malformada | não | DLQ |
| Chave inexistente | não | DLQ, com log de warning. Nenhuma nota é criada ou alterada e o `messageId` não é gravado |
| `messageId` já processado | não | `ack` e log de duplicata |
| Nota já finalizada | não | `ack` e log, sem alterações |
| Erro técnico (por exemplo, banco fora do ar) | sim | Rollback, 3 retries com esperas de 2s, 4s e 8s (até 4 execuções) e depois DLQ |

### 4.6 Idempotência e concorrência

- O consumer abre a transação, trava a linha da nota (`FOR UPDATE`), registra o `messageId` em `processed_messages` e só então decide. O registro e o update da nota acontecem na **mesma transação**.
- Se o `messageId` já existir, nada muda e a mensagem recebe `ack`.
- O update da nota só vale enquanto o status for `IN_ANALYSIS` (guarda de estado final).
- O lock da linha serializa entregas concorrentes da mesma nota.

### 4.7 Outbox e relay

- A nota e a linha do outbox são gravadas na mesma transação. A linha existe apenas enquanto a solicitação está pendente e é apagada na transação que confirma a publicação.
- O `id` da linha é o `messageId` da solicitação.
- Tanto a publicação imediata (API) quanto o relay (worker) usam a mesma função de publicação. Ela reserva a linha com `FOR UPDATE SKIP LOCKED`, publica com *publisher confirm* e apaga a linha antes do commit.
- A publicação imediata é disparada no `afterCommit` da transação que gravou a nota e o outbox (registrado pelo próprio caso de uso, não pelo controller). Ela roda **em outra thread** (executor limitado: 2 a 4 threads e fila de 100), numa transação nova (`REQUIRES_NEW`), então o `POST` não espera o broker. Qualquer falha, ou fila do executor cheia, é registrada em log sem mudar a resposta, e o relay publica depois. Se a transação fizer rollback, nada é publicado.
- O *publisher confirm* é correlacionado, com `mandatory` e *returns*: a publicação só conta como confirmada com `ack` do broker e sem devolução (mensagem roteada para alguma fila). `nack`, timeout (padrão 5s) ou devolução contam como falha.
- O relay acorda a cada tick (padrão 5s), pega até 50 linhas com `next_attempt_at <= now()` e, em caso de falha, faz `attempts + 1`, `next_attempt_at = now + min(2^attempts s, 60s)` (com o valor já incrementado: 2s, 4s, 8s, 16s, 32s e depois 60s) e grava `last_error`. Uma falha numa linha não impede a publicação das outras do lote.
- O timeout de conexão com o RabbitMQ é curto (padrão 2s), para não prender as threads da publicação imediata quando o broker está fora.
- O relay nunca desiste de uma linha.

### 4.8 Consumo da solicitação e chamada ao provedor

O consumer da solicitação roda no `worker` e **não acessa o banco**: ele só lê a mensagem e chama o provedor (3.4).

| Situação | Retry | Destino |
|---|---|---|
| Mensagem malformada (JSON inválido, `messageId`, `invoiceId` ou `accessKey` ausentes) | não | DLQ da solicitação |
| Provedor responde `202` | não | `ack` |
| Provedor responde `4xx` | não | DLQ da solicitação, com log de warning |
| Provedor responde `5xx`, timeout ou conexão recusada | sim | 3 retries com esperas de 2s, 4s e 8s (até 4 execuções) e depois DLQ da solicitação |

- Mesma política de retry e `prefetch = 1` do consumer de resultado (4.5).
- O `X-Correlation-Id` usa o `correlationId` da mensagem; se faltar, usa o `payload.invoiceId`. O `worker` não revalida o formato da chave (a api já validou): uma chave inválida publicada direto na fila é recusada pelo provedor com `4xx` e vai para a DLQ. Um UUID ilegível conta como malformada.
- A chamada é idempotente do lado do provedor, que deduplica pelo `messageId`. Um retry depois de um timeout, em que o provedor chegou a aceitar a primeira chamada, não gera dois resultados diferentes.
- Chamar o provedor não altera o estado da nota. A nota continua `IN_ANALYSIS` até o resultado ser processado.
- Quando a solicitação cai na DLQ, a nota fica em `IN_ANALYSIS` (ver as limitações em 12).

## 5. Dados

Migrações com Flyway. Detalhes e diagrama em [flow.md](flow.md#7-modelo-de-dados).

| Tabela | Colunas principais |
|---|---|
| `invoices` | `id` (PK), `access_key` (UNIQUE, `CHAR(44)`), `status`, `cnpj`, `amount` (`NUMERIC(15,2)`), `issue_date`, `cancelled`, `approved_at`, `rejection_reason`, `created_at`, `updated_at` |
| `outbox` | `id` (PK, messageId), `invoice_id` (FK), `access_key`, `payload` (`JSONB`), `attempts`, `next_attempt_at`, `last_error`, `created_at` |
| `processed_messages` | `message_id` (PK), `processed_at` |

Horários em `timestamptz`.

## 6. Simulador do provedor

Projeto separado em `provider-simulator/`, com `pom.xml` e `Dockerfile` próprios. Ele representa o provedor externo e **não usa o broker**: só fala HTTP.

1. Expõe `POST /enrichment-requests` (3.4). Valida o corpo, deduplica pelo `messageId` da solicitação e responde `202 Accepted` sem dados. Corpo inválido recebe `400`.
2. Depois da resposta, espera um pequeno atraso (padrão 300 ms, configurável).
3. Monta o envelope `EnrichmentResult` (3.2) com `messageId = UUID.nameUUIDFromBytes("result:" + requestMessageId)`, o `correlationId` copiado da solicitação e um resultado **completo e aprovável**:

   | Campo | Valor |
   |---|---|
   | `cnpj` | um CNPJ válido fixo |
   | `amount` | `1234.56` |
   | `issueDate` | a data atual (UTC) |
   | `cancelled` | `false` |

4. Serializa o corpo, calcula o HMAC-SHA256 com `WEBHOOK_HMAC_SECRET` e chama `POST {WEBHOOK_URL}/webhooks/enrichment-results` com `X-Signature: sha256=<hex>` (2.5).
5. Se o webhook responder `5xx`, ou houver timeout ou conexão recusada, reenvia o **mesmo corpo** até 3 vezes (esperas de 1s, 2s e 4s, configuráveis). Respostas `4xx` não são reenviadas e são registradas em log.

O simulador e o `invoice-webhook` compartilham apenas o segredo, vindo do `.env`. Os cenários de rejeição são cobertos pelos testes automatizados, nos quais o próprio teste chama o webhook (ou publica na fila, quando o cenário for do consumer) com o resultado desejado.

## 7. Cenários de teste

Cada cenário vira pelo menos um teste automatizado. Os cenários **T** correspondem aos testes exigidos no desafio, e os **E** são adicionais.

Níveis: **D** domínio (JUnit puro), **A** serviço de aplicação (Mockito), **W** HTTP (MockMvc), **P** persistência (Testcontainers PostgreSQL), **I** integração com o broker real (Testcontainers RabbitMQ + PostgreSQL).

| ID | Cenário | Nível |
|---|---|---|
| T1 | `POST` com chave válida cria a nota em `IN_ANALYSIS`, sem `approvedAt`, e a solicitação é publicada | W, I |
| T2 | Enquanto nenhum resultado for processado, a nota continua em `IN_ANALYSIS` | A, I |
| T3 | Resultado completo e não cancelado aprova a nota, grava os dados e `approvedAt` | D, A, I |
| T4 | Resultado cancelado rejeita a nota, com motivo e sem `approvedAt` | D, A |
| T5 | Resultado sem `cnpj`, sem `amount` ou sem `issueDate` rejeita a nota como incompleta (um teste por campo) | D, A |
| T6 | Duas requisições com a mesma chave, inclusive simultâneas, mantêm uma só nota | P, W |
| T7 | A mesma mensagem de resultado entregue duas vezes não atualiza a nota de novo | A, I |
| T8 | Resultado para chave inexistente não cria nota e vai para a DLQ | A, I |
| T9 | Falha na publicação preserva a nota, e o relay publica a solicitação pendente depois | A, I |
| T10 | Chave com formato inválido recebe `400` | W |
| E1 | `cancelled` ausente rejeita a nota como incompleta | D |
| E2 | CNPJ inválido ou `amount` negativo rejeita a nota como dados inválidos | D |
| E3 | Resultado com `messageId` diferente para nota finalizada não altera nada | A |
| E4 | Mensagem malformada vai direto para a DLQ | I |
| E5 | Erro técnico no processamento faz 3 retries e depois vai para a DLQ | A, I |
| E6 | Chave com espaços nas pontas é aceita e tratada como a mesma chave | W |
| E7 | `POST` com chave repetida devolve `200` com o mesmo `id` e não cria outra linha no outbox | W, P |
| E8 | Depois da publicação confirmada, a linha do outbox é apagada | P, I |
| E9 | Dois relays simultâneos não publicam a mesma linha duas vezes | P |
| E10 | Quando a prioridade se aplica (cancelada e incompleta ao mesmo tempo), o motivo é o de cancelamento | D |
| C1 | Solicitação consumida: o `worker` chama o provedor com o corpo e o `X-Correlation-Id` corretos, recebe `202` e dá `ack` | A, I |
| C2 | Provedor responde `4xx`: a solicitação vai direto para a DLQ da solicitação, sem retry | A |
| C3 | Provedor responde `5xx` ou não responde: 3 retries (2s, 4s e 8s) e depois DLQ da solicitação | A, I |
| C4 | Solicitação malformada vai direto para a DLQ da solicitação, sem chamar o provedor | A |
| H1 | Callback com assinatura válida e resultado bem formado publica o resultado na fila e responde `202` | W, I |
| H2 | Callback sem `X-Signature` ou com assinatura mal formada responde `401` e não publica | W |
| H3 | Callback com corpo alterado depois de assinado (assinatura não confere) responde `401` e não publica | W |
| H4 | Callback assinado com payload malformado responde `400` e não publica (um teste por motivo: JSON inválido, sem `messageId`, sem `accessKey`, `amount` ilegível) | W |
| H5 | Falha na publicação (`nack`, timeout ou devolução) responde `503` | A, W |
| H6 | O mesmo callback enviado duas vezes publica duas mensagens com o mesmo `messageId`, e a nota é atualizada uma única vez | I |
| H7 | A Swagger UI do `invoice-webhook` está disponível e documenta o endpoint em português | W |
| H8 | O perfil `webhook` sobe sem datasource, sem JPA e sem Flyway | W |

Formato dos cenários, com um exemplo:

```
Cenário T7: resultado duplicado não repete a atualização
  Dado uma nota APPROVED com approvedAt = T1
  Quando o mesmo resultado (messageId M1) é entregue de novo
  Então a nota continua APPROVED com approvedAt = T1
  E a mensagem recebe ack
```

### 7.1 Teste de integração com o broker real

Usa Testcontainers (RabbitMQ e PostgreSQL) e cobre publicação, consumo e reentrega.

- **Publicação e consumo:** o `POST` grava a nota, a solicitação chega à fila, o `worker` a repassa por HTTP a um provedor de teste (servidor HTTP no papel do provedor), que responde `202` e depois chama o `invoice-webhook` com o corpo assinado. O resultado é publicado na fila e a nota é aprovada.
- **Resultado direto na fila:** os cenários do consumer (E4 malformada, E5 erro técnico, T8 chave inexistente) continuam publicando o resultado **direto na fila**, porque o webhook nunca deixaria passar uma mensagem malformada. É a defesa em profundidade do consumer.
- **Reentrega provocada pelo broker:** um consumidor de teste, com *ack* manual, recebe o resultado e fecha o canal **sem confirmar**. O RabbitMQ reentrega a mensagem com `redelivered = true`, e o teste confere a flag e a segunda entrega.
- Chamar a função de processamento duas vezes **não** conta como demonstração de reentrega. Por isso a reentrega vem do broker, como acima.
- **Reentrega no consumer real:** um interceptador só de testes, posicionado **fora** do retry do consumer, deixa o consumer real processar e gravar normalmente na primeira entrega e, depois do commit, lança uma exceção que faz o broker recolocar a mensagem na fila (`nack` com requeue). O broker reentrega com `redelivered = true`, e o teste confere que a nota foi atualizada uma única vez (mesmo `approvedAt` depois da segunda entrega), que há uma linha em `processed_messages` e que a DLQ está vazia. Não exige mudança no código de produção além de a configuração do container do listener ser declarada explicitamente em `config/messaging`.
- Se esse mecanismo se mostrar inviável, o plano de reserva é manter só o teste com o consumidor de teste, descrito acima. A garantia de não repetir a atualização continua coberta no nível de serviço (T7, A).

## 8. Observabilidade e execução local

- Logs em `stdout` e em português (por exemplo `Nota aprovada`, `Resultado duplicado ignorado`, `Chave inexistente, mensagem enviada para a DLQ`), com `correlationId` (o `invoiceId`) propagado. Sem CNPJ ou valor nos logs em nível `info`.
- Health check com Spring Actuator.
- Configuração por `application.yml` e variáveis de ambiente. Valores de exemplo em `.env.example`, sem credenciais reais versionadas.
- Docker Compose sobe `postgres`, `rabbitmq`, `api`, `worker`, `invoice-webhook` e `provider-simulator`, com *healthchecks* e dependências entre eles. `api`, `worker` e `invoice-webhook` usam a mesma imagem, com perfis diferentes.
- Portas: `api` 8080, `worker` 8081, `invoice-webhook` 8082 e `provider-simulator` 8083. O Swagger do webhook fica em `localhost:8082/swagger-ui.html`.
- O segredo do webhook (`WEBHOOK_HMAC_SECRET`) vem do `.env`, com valor de exemplo apenas local no `.env.example`. Ele não tem valor padrão na aplicação: sem a variável, o perfil `webhook` não sobe. O `worker` conhece `PROVIDER_BASE_URL`, e o simulador conhece `WEBHOOK_URL`.

Parâmetros configuráveis, com os padrões:

| Parâmetro | Padrão |
|---|---|
| Tick do relay | 5s |
| Tamanho do lote do relay | 50 |
| Teto do backoff do relay | 60s |
| Timeout do publisher confirm | 5s |
| Timeout de conexão com o RabbitMQ | 2s |
| Espera por uma conexão do pool do banco | 5s |
| Retries dos consumers (resultado e solicitação) | 3 (esperas de 2s, 4s e 8s; `RESULT_RETRY_*` e `REQUEST_RETRY_*`) |
| Timeout de conexão e de resposta na chamada ao provedor | 2s e 5s (`PROVIDER_CONNECT_TIMEOUT`, `PROVIDER_READ_TIMEOUT`) |
| URL base do provedor | `http://localhost:8083` (`PROVIDER_BASE_URL`) |
| Atraso do simulador | 300 ms |
| Reenvios do callback pelo simulador | 3 (esperas de 1s, 2s e 4s) |

## 9. Arquitetura do código

Segue a skill `spring-hexagonal-architecture`, pacote base `dev.matheus.invoice`.

- `domain`: `Invoice` (com `approve` e `reject` como comportamento), `InvoiceStatus`, `AccessKey` e `Cnpj` como value objects, e as exceções de negócio.
- `application/service`: criação e consulta de notas, relay do outbox, processamento do resultado, repasse da solicitação ao provedor e publicação do resultado recebido pelo webhook.
- `application/port/out`: repositório de notas, outbox, publicação da solicitação, registro de mensagens processadas, relógio, `ProviderClient` (chamada HTTP ao provedor) e `EnrichmentResultPublisher` (publicação do resultado na fila).
- `adapter/in/web`: `api` (interface `InvoiceApi` com as anotações Swagger/OpenAPI), `controller` (implementa a interface), `dto` e tratamento de erros. O endpoint do webhook fica em `adapter/in/web/webhook` (interface com Swagger, controller, DTO do envelope e verificação do HMAC).
- `adapter/in/messaging`: consumers do resultado e da solicitação.
- `adapter/in/scheduler`: o job do relay.
- `adapter/out/persistence`, `adapter/out/messaging`, `adapter/out/http`: JPA, publisher RabbitMQ e client HTTP do provedor.

Perfis do Spring: `api` e `worker` usam banco e RabbitMQ. O perfil `webhook` usa **só** RabbitMQ: exclui as autoconfigurações de DataSource, JPA e Flyway e não registra beans de persistência, relay nem consumers. Cada perfil ativa apenas os beans de que precisa. O HMAC é verificado por um componente do adapter web, e o domínio não conhece o webhook.

Versões: Java 25 LTS, Maven, Spring Boot na versão estável da linha indicada pela skill (confirmada na documentação oficial antes de gerar o `pom.xml`).

Ferramentas: Lombok, Flyway, Testcontainers e Swagger/OpenAPI (springdoc), com a Swagger UI disponível na `api` para testar os endpoints. A compatibilidade do springdoc com a versão do Spring Boot é conferida antes de gerar o `pom.xml`.

Da skill, **não** entram `port/in`, MapStruct, eventos de domínio (`pullEvents`), envelope completo, Micrometer, OpenTelemetry, Pact nem `domain/service`. O pacote `shared` só é criado quando houver algo realmente compartilhado. `Invoice` é separada da entidade JPA, com mapper manual. `outbox` e `processed_messages` não têm modelo de domínio. As decisões estão consolidadas no `CLAUDE.md`.

## 10. Plano de implementação

Fatias verticais. Cada uma começa pelos cenários correspondentes escritos como testes e termina com eles passando. O acompanhamento item a item está em [checklist.md](checklist.md).

| Fatia | Entrega | Cenários |
|---|---|---|
| 1 | Projeto Maven, Docker Compose, migrações Flyway e health check | — |
| 2 | Domínio `Invoice`, `AccessKey` e `Cnpj`, com as regras de aprovação e rejeição | T3, T4, T5, E1, E2, E10 |
| 3 | `POST` e `GET` com persistência e outbox na mesma transação | T1, T2, T6, T10, E6, E7 |
| 4 | Relay do outbox e publisher no RabbitMQ | T9, E8, E9 |
| 5 | Consumer do resultado, idempotência, retry e DLQ | T7, T8, E3, E4, E5 |
| 6A | Chamada ao provedor pelo `worker`: consumer da solicitação, client HTTP, retry e DLQ da solicitação | C1–C4 |
| 6B | Perfil `webhook` (`invoice-webhook`): HMAC, validação do payload, publicação do resultado e Swagger | H1–H5, H7, H8 |
| 6C | Simulador do provedor (HTTP + callback assinado) e Docker Compose com os seis serviços | — |
| 7 | Teste de integração com o broker real, incluindo a reentrega e o fluxo pelo webhook | T1, T3, T7, T8, T9, C1, C3, H1, H6 em nível I |
| 8 | Validação e revisão: agentes de segurança e de boas práticas, e aderência à spec | — |
| 9 | README em português, com o registro de uso de IA | — |

## 11. Fora do escopo

Integração com fornecedor fiscal real, consulta à SEFAZ, validação fiscal completa da chave, infraestrutura em nuvem, autenticação e autorização, interface gráfica e ferramentas externas de observabilidade.

## 12. Limitações conhecidas

- Nota sem resposta do provedor fica em `IN_ANALYSIS` indefinidamente, sem timeout.
- A entrega é *at-least-once*: a solicitação pode ser publicada duas vezes, e o provedor deve deduplicar pelo `messageId`.
- A espera do retry acontece dentro do worker e bloqueia aquele consumer (`prefetch = 1`). Filas de retry com TTL evitariam isso.
- O relay mantém uma transação aberta enquanto publica. Um broker lento segura os locks das linhas.
- A publicação imediata depende de um executor limitado. Se o broker ficar lento e a fila dele encher, as solicitações excedentes só são publicadas pelo relay (até 5s depois).
- Apagar as linhas do outbox após a publicação não deixa histórico de publicação.
- O reprocessamento da DLQ é manual.
- Uma mensagem de resultado sem `cancelled` rejeita a nota de forma irreversível, porque `REJECTED` é um estado final.
- O HMAC protege a integridade e a autenticidade do corpo, mas não tem timestamp nem nonce: um callback válido capturado pode ser reenviado. O efeito é limitado pela idempotência do consumer (`messageId` e estado final), e não por uma janela de tempo.
- O segredo do HMAC é simétrico e compartilhado entre o provedor e o `invoice-webhook`. Não há rotação automática.
- Se o callback nunca chegar (provedor com falha, ou reenvios esgotados), a nota fica em `IN_ANALYSIS` indefinidamente, sem timeout. O mesmo vale quando a solicitação cai na DLQ da solicitação.
- O reprocessamento da DLQ da solicitação também é manual.
- O webhook publica a mensagem antes de responder `202`. Se a resposta se perder, o provedor reenvia e a mensagem é publicada duas vezes, e o consumer a descarta.
