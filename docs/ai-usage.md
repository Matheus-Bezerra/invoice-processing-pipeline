# Registro de uso de IA

Registro do que a IA gerou, do que foi revisado ou corrigido e do que ainda precisa de verificação. Atualizado ao fim de cada fatia.

## Fatia 1 — Projeto e infraestrutura

### Versões e fontes consultadas

| Item | Versão | Fonte |
|---|---|---|
| Java | 25 (LTS) | Boot 4.1 suporta Java 17 a 26 (documentação do Spring Boot, via Context7) |
| Spring Boot | 4.1.2 | `start.spring.io/metadata/client` (último patch estável da linha 4.1) e Maven Central |
| Maven Wrapper | 3.10.0 (definido pelo `start.spring.io`) | `.mvn/wrapper/maven-wrapper.properties` |
| springdoc-openapi | 3.2.0 (`springdoc-openapi-starter-webmvc-ui`) | README/CHANGELOG do springdoc (Context7) e Maven Central: o pai da 3.2.0 é o Boot 4.1.2 |
| Testcontainers | 2.x, gerenciada pelo BOM do Boot (`testcontainers-postgresql`, `testcontainers-rabbitmq`, `testcontainers-junit-jupiter`) | Documentação do Testcontainers 2.0.3 (Context7) e `start.spring.io` |
| PostgreSQL | imagem `postgres:18-alpine` | Docker Hub (tag verificada) |
| RabbitMQ | imagem `rabbitmq:4.4-management` | Docker Hub (tag verificada) |
| JDK/JRE nas imagens | `eclipse-temurin:25-jdk` e `25-jre-alpine` | Docker Hub (tags verificadas) |

### O que a IA gerou

- Esqueleto do projeto (via `start.spring.io`), `pom.xml` ajustado, `application*.yml`, migrações Flyway V1–V3, `Dockerfile`, `docker-compose.yml`, `.env.example`, `.gitignore` e o teste de smoke.

### O que foi revisado ou corrigido

- O `start.spring.io` gerou o pai como `4.1.2.RELEASE`, que **não existe** no Maven Central; corrigido para `4.1.2`.
- A documentação consultada primeiro indicava Boot 4.1.0 e springdoc 3.1.x; as versões mais novas foram confirmadas direto no `start.spring.io` e no Maven Central.
- O plano original citava artefatos do Boot 3 e do Testcontainers 1.x (`spring-boot-starter-web`, `postgresql`); no Boot 4 são `spring-boot-starter-webmvc` e starters modulares, e no Testcontainers 2.x os módulos têm prefixo `testcontainers-`.
- Tags `latest` do teste trocadas por tags fixas.
- No `application-worker.yml`, o servidor web do worker fica na porta 8081 só para o Actuator.

### Verificação executada

- `./mvnw verify`: 2 testes (tabelas criadas pelo Flyway e health `UP`) passaram, com Testcontainers.
- `docker compose up --build`: `postgres`, `rabbitmq`, `api` e `worker` ficaram `healthy`.
- `/actuator/health` retornou `UP` na api (8080) e no worker (8081); Swagger UI abriu na api e não existe no worker.
- `\dt` no Postgres mostrou `invoices`, `outbox`, `processed_messages` e `flyway_schema_history`.

### O que falta verificar

- Nada pendente nesta fatia. Os índices e constraints das migrações serão exercitados pelos testes das fatias 3 a 5.

## Fatia 2 — Domínio

### Fontes consultadas

| Item | Fonte |
|---|---|
| Cálculo do dígito verificador do CNPJ alfanumérico (módulo 11, valor = ASCII − 48, pesos 2 a 9 da direita para a esquerda; exemplo `12ABC34501DE35`) | FAQ do CNPJ alfanumérico da Receita Federal (gov.br), conferido por busca na web |
| `@Nested`, `@ParameterizedTest`, `@NullAndEmptySource` | Documentação do JUnit (Context7) |

### O que a IA gerou

- Testes primeiro (`AccessKeyTest`, `CnpjTest`, `InvoiceTest`), confirmados vermelhos (erro de compilação) antes da implementação.
- `InvoiceStatus`, `ProcessingOutcome`, `AccessKey`, `Cnpj`, `EnrichmentData`, `Invoice` e as exceções `InvalidAccessKeyException` e `InvalidCnpjException`.

### Decisões tomadas com o autor

- A decisão (cancelada, incompleta, inválida, aprova) fica em `Invoice.applyEnrichment`; `approve` e `reject` são privados e só mudam o estado.
- Nota finalizada devolve `ProcessingOutcome.IGNORED_ALREADY_FINAL` em vez de lançar exceção; o service da fatia 5 faz `ack` + log.
- Os testes de nível A de T3, T4 e T5 ficam para a fatia 5, quando o service de processamento existir.
- `EnrichmentData.cnpj` é `String` e `Invoice.cnpj` também, porque um CNPJ inválido é gravado na nota rejeitada.

### O que foi revisado ou corrigido

- Um caso de teste "CNPJ com todos os dígitos iguais é inválido" foi removido: a spec não define essa regra e o cálculo oficial aceita `00000000000000`. Não inventei regra além da spec.
- O exemplo da spec `12ABC34501DE35` foi validado pelo algoritmo e confere com o exemplo da Receita.

### Verificação executada

- `./mvnw verify`: 48 testes, 0 falhas (inclui o smoke da fatia 1).
- `grep` confirmou que o pacote `domain` não importa Spring, JPA nem AMQP.

### O que falta verificar

- Os testes de nível A de T3, T4 e T5 (fatia 5).
- A regra do CNPJ foi conferida em fonte secundária e no FAQ da Receita; vale revisar o algoritmo contra o PDF oficial de cálculo antes da entrega.

## Fatia 3 — API e outbox

### Fontes consultadas

| Item | Fonte |
|---|---|
| Pacotes de teste do Boot 4 (`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`) e Jackson 3 (`tools.jackson.databind.json.JsonMapper`) | Conferidos direto nos jars do Maven local (`spring-boot-webmvc-test`, `spring-boot-jackson`) |
| `@MockitoBean` | `org.springframework.test.context.bean.override.mockito` (Spring Framework 7) |

### O que a IA gerou

- Testes primeiro: `InvoiceRestoreTest` (D), `InvoiceServiceTest` (A: T2 e outbox), `InvoiceControllerTest` (W: T1, T10, E6, E7, GETs) e `InvoiceServicePersistenceTest` (P: T6, E7, atomicidade). Os testes foram escritos antes do código de produção; a compilação falhou até as classes existirem.
- `Invoice.restore`, `InvoiceNotFoundException`, ports (`InvoiceRepository`, `OutboxPort`, `ClockPort`), `InvoiceService`, `CreateInvoiceResult`, `EnrichmentRequestMessage`, adapters de persistência (JPA com `INSERT ... ON CONFLICT DO NOTHING` e `JdbcClient` para o outbox), `SystemClockAdapter`, `InvoiceApi`, `InvoiceController`, DTOs e `ApiExceptionHandler`.

### Decisões tomadas com o autor

- Um único `InvoiceService` (criação e consultas), sem `OutboxMessage`: o `OutboxPort` recebe os valores soltos. Fica só o record do envelope, que a fatia 4 reaproveita.
- Testes W com `@WebMvcTest` e service mockado; a persistência real fica nos testes P.
- `Invoice` via JPA e outbox via `JdbcClient`.
- O controller converte a chave com `AccessKey.of` (trim + 44 dígitos) e o service recebe o value object. Assim T10 e E6 são testados de verdade no nível W e a validação fica num único lugar (sem Bean Validation duplicada).

### O que foi revisado ou corrigido

- O `sed -i` do macOS exige `-i ''`; a primeira tentativa falhou no meio de uma cadeia de comandos e os arquivos tiveram de ser regravados.
- `TestcontainersConfiguration` virou `public` para ser importada pelo teste de persistência em outro pacote.
- O relógio trunca para microssegundos, porque o PostgreSQL guarda microssegundos e o `createdAt` lido seria diferente do gravado.
- O primeiro `docker compose up --build -d` deixou o container `api` antigo no ar (a fatia 1, sem as rotas) e o `curl` devolveu 404 em tudo; `docker compose up -d --force-recreate api worker` resolveu. Os testes automatizados não pegariam isso.

### Verificação executada

- `./mvnw verify`: 81 testes, 0 falhas (29 novos).
- `grep`: `domain` não importa Spring, JPA, AMQP nem Jackson; `application` não importa `adapter`, `RabbitTemplate` nem JPA.
- `docker compose up` e `curl`: `POST` novo (201 + `Location`), repetido (200, mesmo `id`), chave inválida (400 `problem+json`), `GET` por chave, `GET` 404 e `GET` com id inválido (400). A Swagger UI abre na `api` e não existe no `worker`. O `psql` mostrou 1 nota e 1 linha no outbox (`invoice.enrichment.requested`, `attempts = 0`). O OpenAPI saiu com textos em português, inclusive nas respostas de sucesso.

### O que falta verificar

- Erros genéricos do framework que não têm handler próprio (405, 415, 406) saem em `problem+json`, mas com `title` e `detail` padrão do Spring, em inglês. Só os erros previstos na spec foram traduzidos.
- A linha do outbox só é gravada; a publicação (imediata e pelo relay) é da fatia 4.

## Fatia 4 — Relay e publisher

### Fontes consultadas

| Item | Fonte |
|---|---|
| `CorrelationData.getFuture()`, `getReturned()` e `Confirm` (record com `ack()`/`reason()`) | Bytecode do `spring-rabbit-4.1.2.jar` no Maven local (`javap`) |
| Garantia de que a mensagem devolvida é preenchida antes do `ack` (confirms + returns) | Referência do Spring AMQP, seção "Correlated Publisher Confirms and Returns" (Context7) |
| `@Scheduled(fixedDelayString = "1s")` aceita o formato simples de duração | `ScheduledAnnotationBeanPostProcessor` do `spring-context-7.0` usa `DurationFormatterUtils.detectAndParse` (conferido no jar) |

### O que a IA gerou

- Testes primeiro, confirmados vermelhos (erro de compilação) antes do código: `OutboxPublisherServiceTest` (A: T9, backoff, linha já reservada, falha isolada no lote), `ImmediatePublication` em `InvoiceServiceTest` (A: publica só depois do commit, falha não propaga, chave repetida não publica), `OutboxRelayPersistenceTest` (P: E8 pelo relay e pela publicação imediata, T9 com backoff no banco, E9 com dois relays simultâneos) e `RabbitEnrichmentRequestPublisherTest` (RabbitMQ real: topologia, propriedades da mensagem, mensagem não roteável vira falha, rejeição na fila de resultado vai para a DLQ).
- `PendingRequest`, `EnrichmentRequestPublisher`, novos métodos do `OutboxPort` (`lockById`, `lockDue`, `delete`, `markFailed`), `OutboxPublisherService`, `OutboxRelaySettings`, `RabbitEnrichmentRequestPublisher`, `MessagePublishException`, `OutboxRelayJob`, `MessagingTopology`, `RabbitTopologyConfig`, `OutboxConfig`, `SchedulingConfig` e as propriedades no `application.yml`.

### Decisões tomadas com o autor

- **Publicação imediata no `afterCommit`** registrado em `InvoiceService.create`, e não no controller: num desenho orientado a eventos o disparo fica ligado ao commit da transação que gravou o evento (a linha do outbox), vale para qualquer ponto de entrada e não publica nada se houver rollback. Ela roda em `REQUIRES_NEW` (dentro do `afterCommit` os recursos da transação original ainda estão ligados à thread) e a exceção é capturada para o `POST` continuar `201`.
- **Publisher confirm correlacionado + returns com `mandatory`**, em vez de `simple` + `waitForConfirmsOrDie`: sem os returns, uma mensagem sem fila para rotear recebe `ack` e a linha do outbox seria apagada com a mensagem perdida.
- **Backoff do relay sem limite de tentativas** (2s, 4s, 8s, 16s, 32s e depois 60s). O autor perguntou se não seriam 4 tentativas no total; o limite de 4 execuções (1 + 3 retries de 2s, 4s e 8s) é do consumer do resultado (fatia 5). O relay não pode desistir, porque o outbox não tem DLQ e a nota ficaria em `IN_ANALYSIS` para sempre.

### O que foi revisado ou corrigido

- **`POST` levou 60s com o broker fora** no teste manual com Compose: a publicação imediata roda na thread do request e o timeout de conexão padrão do `CachingConnectionFactory` é 60s. Os testes automatizados não pegaram isso. Corrigido com `spring.rabbitmq.connection-timeout: 2s` (variável `RABBITMQ_CONNECTION_TIMEOUT`); depois disso o primeiro `POST` levou cerca de 2s e os seguintes, cerca de 30 ms. Registrado como limitação na spec.
- O teste T9 de nível A tinha um mock de `InvoiceRepository` que o `OutboxPublisherService` nem recebe, o que tornava a verificação "a nota não é tocada" vazia. Removido; a preservação da nota é conferida no teste de persistência (status `IN_ANALYSIS` no banco depois da falha).
- `InvoiceServicePersistenceTest` (fatia 3) passou a usar um publisher mockado que falha, porque agora a publicação imediata apagaria a linha do outbox que esses testes contam.
- O backoff usa o valor de `attempts` já incrementado (primeira falha espera 2s). A spec permitia as duas leituras; a escolha ficou registrada na seção 4.7.

### Verificação executada

- `./mvnw verify`: 108 testes, 0 falhas (27 novos, contando o nack, a publicação em segundo plano e a saturação do executor).
- `grep`: `application` não importa `org.springframework.amqp`, `adapter` nem `config`; `domain` continua sem Spring, JPA e Jackson.
- `docker compose up --build`: `rabbitmqctl` mostrou o exchange `invoice.enrichment` e a DLX `invoice.enrichment.dlx` (direct), as três filas e os argumentos de dead letter na fila de resultado. Um `POST` com o broker no ar devolveu `201`, a linha do outbox foi apagada e a mensagem chegou em `invoice.enrichment.request`; uma linha que tinha ficado da fatia 3 foi publicada pelo relay do worker.
- Com `docker compose stop rabbitmq`: `POST` devolveu `201`, a nota ficou `IN_ANALYSIS` e a linha do outbox acumulou tentativas com `last_error` e esperas de 2s, 4s, 8s e 16s nos logs. Depois de `docker compose start rabbitmq`, o relay publicou a mesma solicitação e apagou a linha.

### O que falta verificar

- Testes de nível I de T9 (falha de publicação seguida de recuperação com o broker real) e E8 ficam para a fatia 7.
- Ainda não há consumidor da fila de solicitação (simulador, fatia 6); as mensagens publicadas ficam acumuladas em `invoice.enrichment.request` no ambiente local.
- Nada pendente sobre o nack (ver abaixo, "Ajustes depois da revisão do autor").

### Ajustes depois da revisão do autor

- **Teste de nack com o broker real:** `nackFromTheBrokerIsAFailure` recria a fila de solicitação com `x-max-length=1` e `x-overflow=reject-publish`; a primeira publicação é confirmada e a segunda recebe `nack` do RabbitMQ, que vira `MessagePublishException`. O teste restaura a topologia no `finally`.
- **Tick do relay de 1s para 5s** (`OUTBOX_RELAY_TICK`), atualizado em `application.yml`, `spec.md`, `flow.md` e `CLAUDE.md`. Com a publicação imediata cobrindo o caminho normal, o relay só recupera falhas, então 5s basta.
- **O `POST` esperava o RabbitMQ (dúvida do autor, procedente):** a publicação imediata rodava na thread do request, no `afterCommit`, antes da resposta. O outbox existe justamente para a resposta não depender do broker. Corrigido com a opção A escolhida pelo autor: o `afterCommit` só entrega a tarefa a um `ExecutorService` limitado (`ImmediatePublishConfig`: 2 a 4 threads, fila de 100, `AbortPolicy`); `RejectedExecutionException` e falhas viram log e o relay recupera. Novos testes: o `create` termina com o publisher travado, a tarefa só é submetida depois do commit, fila cheia não falha o request. Medido com o RabbitMQ parado: `POST` de 24 a 105 ms (antes 2 s no primeiro e 60 s antes do timeout de conexão).
- **Teste de saturação do executor** (`saturatedExecutorLeavesTheExcessInTheOutboxAndTheRelayPublishesIt`, com o executor real): o publisher fica travado, 120 notas são criadas (todas com sucesso, sem esperar), o executor rejeita o excedente e as linhas rejeitadas ficam no outbox; ao liberar o publisher, `publishDue` publica exatamente o excedente, e no fim há 120 `messageId` publicados, sem repetição, e o outbox vazio. O teste calcula o excedente a partir do contador do `ThreadPoolExecutor` em vez de fixar o número. Na execução completa o log registrou 17 descartes (esperado cerca de 16 pelos limites 2 + 100 + 2; a diferença de um não afeta o teste e não foi investigada).
- A limitação "o primeiro `POST` espera o timeout de conexão" foi removida da spec e do flow; entrou a de que o executor é limitado e o excedente depende do relay.

## Fatia 5 — Consumer do resultado

### Fontes consultadas

| Item | Fonte |
|---|---|
| Retry do listener no Spring AMQP 4 (`RetryInterceptorBuilder.stateless()`, `maxRetries`, `backOffOptions`, `configureRetryPolicy`, `recoverer`): o Spring AMQP 4 trocou o Spring Retry pelo `org.springframework.core.retry` do Spring Framework 7 | Bytecode do `spring-rabbit-4.1.2.jar` (`javap`) |
| `RetryPolicy.Builder.predicate(...)` e o `RetryTemplate` lançando `RetryException` na hora para exceção não retentável (o interceptor então chama o recoverer) | Bytecode do `spring-core-7.0.8.jar` e do `StatelessRetryOperationsInterceptor` |
| `JacksonException.getPath()` / `Reference.getPropertyName()` (Jackson 3) | Bytecode do `jackson-core` 3 no Maven local |

### O que a IA gerou

- Testes primeiro, confirmados vermelhos (erro de compilação) antes do código: `EnrichmentResultServiceTest` (A: T3, T4, T5 por campo, T7, T8, E3, E5 e a guarda de estado perdendo a corrida), `EnrichmentResultPersistenceTest` (P: aprovação e rejeição gravadas, T7, E3, T8 sem gravar o `messageId`, 8 entregas simultâneas com `messageId` diferentes para a mesma nota aplicando só uma) e `EnrichmentResultConsumerTest` (I, consumer real com o perfil `worker`: caminho feliz, E4 com 7 variações de mensagem malformada, T8 na DLQ sem retry e E5 com 4 execuções e DLQ).
- `ProcessedMessageStore` e `ProcessedMessageJdbcAdapter` (`INSERT ... ON CONFLICT DO NOTHING`), `findByAccessKeyForUpdate` (`SELECT ... FOR UPDATE`) e `updateEnrichment` (com `WHERE status = IN_ANALYSIS`) no `InvoiceRepository`, `EnrichmentResultService`, `EnrichmentResultCommand`, `ResultHandling`, `UnknownAccessKeyException`, `EnrichmentResultMessage`, `EnrichmentResultConsumer`, `ResultConsumerConfig` e as propriedades `invoice.messaging.result-retry.*` (`RESULT_RETRY_*`).

### Decisões tomadas com o autor

- `payload.accessKey` presente mas fora do formato de 44 dígitos conta como **mensagem malformada** (DLQ, sem consultar o banco).
- **Erro técnico é qualquer exceção que não seja rejeição.** Malformada e chave inexistente viram `AmqpRejectAndDontRequeueException` e não fazem retry; o resto faz 3 retries e vai para a DLQ.
- Testes P e I já nesta fatia, além dos A pedidos no checklist. As esperas do retry ficaram configuráveis para o teste I rodar com 100 ms.

### Decisões de implementação

- O container do consumer é declarado em `config/messaging/ResultConsumerConfig` (spec 7.1, para a fatia 7 poder pôr um interceptador fora do retry): `prefetch = 1`, `ack` automático depois do retorno do listener (portanto depois do commit), `defaultRequeueRejected = false` e retry stateless no `adviceChain`.
- O predicado do retry percorre a cadeia de causas, porque o container embrulha a exceção do listener em `ListenerExecutionFailedException`.
- O recoverer é próprio em vez do `RejectAndDontRequeueRecoverer`, que loga "Retries exhausted" em inglês até para uma mensagem malformada que nem fez retry. O próprio loga `Tentativas esgotadas, mensagem enviada para a DLQ` só quando houve retry.
- Nota finalizada com `messageId` novo grava o `messageId` em `processed_messages` (a ordem do flow.md: o registro vem antes da checagem de status). Chave inexistente não grava (spec 4.5).

### O que foi revisado ou corrigido

- O `UPDATE` da decisão começou como query nativa; foi trocado por JPQL antes de rodar, porque um parâmetro nulo (por exemplo `cnpj` ausente numa rejeição) pode chegar ao PostgreSQL sem tipo numa query nativa. Em JPQL o tipo vem do atributo da entidade.
- O log de mensagem malformada usava `getOriginalMessage()` do Jackson, que inclui o valor recebido (por exemplo `"abc"` em `amount`), e depois `getPathReference()`, que expõe o nome da classe Java. Ficou só o caminho do campo (`payload.amount`). Quando o JSON é legível, os ids do log vêm do envelope, e não das propriedades AMQP (que podem vir vazias).
- O interceptor de retry era um bean `MethodInterceptor`; virou método privado da configuração, sem bean sem uso fora dali.
- De novo, o `docker compose up --build -d` não recriou a `api` e o `worker` (mesmo problema da fatia 3); foi preciso `--force-recreate`.

### Verificação executada

- `./mvnw verify`: 134 testes, 0 falhas (26 novos).
- Os logs dos testes mostraram cada caminho: as 7 variações de malformada, `Chave inexistente, mensagem enviada para a DLQ`, uma única passagem por `Tentativas esgotadas` (E5) e `Resultado duplicado ignorado`.
- `grep`: `application` não importa `org.springframework.amqp`, `adapter` nem `config`; `domain` continua sem Spring, JPA e Jackson.
- `docker compose up` e API HTTP do RabbitMQ: `POST` criou a nota, o resultado publicado em `invoice.enrichment` (routing key `result`) a aprovou; o mesmo resultado de novo gerou `Resultado duplicado ignorado`, com o mesmo `approvedAt` e uma linha em `processed_messages`; `isto nao e json` foi para `invoice.enrichment.result.dlq` (conferido com `rabbitmqctl list_queues`; a API de gerenciamento mostrou 0 por alguns segundos, porque as estatísticas dela atualizam com atraso).
- E5 com `docker compose stop postgres`: as 4 execuções terminaram às 17:43:40, 17:44:12, 17:44:46 e 17:45:24 (intervalos de 32s, 34s e 38s = esperas de 2s, 4s e 8s **mais 30s do `connection-timeout` padrão do Hikari** em cada tentativa) e a mensagem foi para a DLQ.

### O que falta verificar

- Teste T7 com reentrega provocada pelo broker (fatia 7).
- O log `Tentativas esgotadas` usa o `messageId`/`correlationId` das propriedades AMQP, que vieram vazias na publicação manual. O publisher do resultado no `invoice-webhook` (fatia 6B) deve preencher essas propriedades, como o publisher da solicitação já faz.
- Os logs de falha do listener do próprio Spring AMQP (`Execution of Rabbit message listener failed`) saem em inglês e com stack trace; só as mensagens da aplicação estão em português.

### Ajustes depois da revisão do autor

- **Espera por conexão do pool do banco de 30s para 5s** (`spring.datasource.hikari.connection-timeout`, variável `DB_CONNECTION_TIMEOUT`, em milissegundos porque o Hikari recebe um `long`; vale para api e worker). O autor perguntou se 5s não cortaria consultas lentas. Não corta: o valor limita só a espera para **pegar** uma conexão do pool, e não a duração da consulta. Com conexões livres, pegar uma leva microssegundos; a espera só cresce com o banco fora (sem chance de sucesso) ou com o pool esgotado (no worker são 2 threads para 10 conexões). Adicionado à tabela de parâmetros da spec (seção 8).
- E5 repetido com `docker compose stop postgres`: a 1ª execução falhou na hora (17:52:05, conexão derrubada pelo desligamento) e as outras às 17:52:12, 17:52:21 e 17:52:34 (esperas de 2s, 4s e 8s + 5s do pool). DLQ em **29s, contra 134s antes**. Um `POST` com o banco fora devolveu `500` em 5,1s.
- `./mvnw verify` depois do ajuste: 134 testes, 0 falhas.

## Fatia 6A — Chamada ao provedor pelo `worker`

### Fontes consultadas

| Item | Fonte |
|---|---|
| `JdkClientHttpRequestFactory` (`setReadTimeout(Duration)`, construtor com `HttpClient`) e `RestClient.builder()` | Bytecode do `spring-web-7.0.8.jar` (`javap`). `RestClient.builder()` basta, sem `spring-boot-starter-restclient` nem dependência nova |
| Retry stateless e recoverer do listener | Já conferidos na fatia 5 (`spring-rabbit-4.1.2`); só extraídos para `ListenerRetry` |

### O que a IA gerou

- Testes primeiro, confirmados vermelhos (erro de compilação): `EnrichmentRequestForwardingServiceTest` (A), `EnrichmentRequestConsumerUnitTest` (A: C1 a C4, 7 variações de malformada), `ProviderHttpClientTest` (provedor fake com `HttpServer` do JDK: 202, 400/404/422, 500/502/503, resposta lenta, conexão recusada, mensagens sem corpo) e `EnrichmentRequestConsumerTest` (I, RabbitMQ real: C1 a C4 com 100 ms de espera).
- `ProviderClient` e as exceções `ProviderRejectedException` e `ProviderUnavailableException`, `EnrichmentRequestForwardingService` e `ForwardEnrichmentRequestCommand`, `ProviderHttpClient`, `EnrichmentRequestConsumer`, `RequestConsumerConfig`, `ListenerRetry` (extraído de `ResultConsumerConfig` sem mudar o comportamento), a DLQ `invoice.enrichment.request.dlq` na topologia e as propriedades `invoice.provider.*` e `invoice.messaging.request-retry.*`.

### Decisões tomadas com o autor

- Malformada é só o que a spec diz (JSON inválido, falta `messageId`, `payload.invoiceId` ou `payload.accessKey`, ou UUID ilegível). A chave **não** é revalidada: o `worker` só repassa, a api já validou e um `4xx` do provedor leva à DLQ.
- `correlationId` é opcional: sem ele, o `X-Correlation-Id` usa o `invoiceId`. Ele serve só para rastreio, não para idempotência.
- Testes em nível A, do adapter HTTP e I (consumer real, RabbitMQ real e provedor fake).
- A idempotência do `worker` é delegada ao provedor (deduplica pelo `messageId`). O corpo vai idêntico, sem alteração.

### O que foi revisado ou corrigido

- Os 25 testes de contexto quebraram na primeira execução: `EnrichmentRequestForwardingService` existia em todos os perfis, mas o `ProviderClient` só no `worker`. O serviço passou a ser `@Profile("worker")`.
- Os testes C3 de nível I falharam (contagens 5 e 3 em vez de 4): o `HttpServer` do JDK atende com uma única thread, então chamadas lentas se enfileiravam e a contagem vazava para o teste seguinte. O erro era do teste; o servidor fake passou a usar um pool de threads e o teste espera as 4 chamadas.
- O redirecionamento HTTP foi desligado no client (`Redirect.NEVER`): `3xx` conta como falha técnica, e o corpo não segue para outro destino.
- O log `Tentativas esgotadas` passou a incluir a fila, porque agora há dois consumers.

### Verificação executada

- `./mvnw verify`: 163 testes, 0 falhas (29 novos).
- `grep`: `application` não importa `org.springframework.amqp`, `web`, `http`, `adapter` nem `config`; `domain` continua limpo; os logs do consumer e do serviço não levam chave nem corpo.
- Compose (`docker compose down` e `up --build --force-recreate`): `rabbitmqctl` mostrou `invoice.enrichment.request` com DLX e `request.dlq`. Um `POST /invoices` sem provedor no ar devolveu `201`; o worker esgotou as tentativas (`Tentativas esgotadas ... fila=invoice.enrichment.request`), a mensagem foi para `invoice.enrichment.request.dlq` e a nota ficou `IN_ANALYSIS`.
- O `docker compose down` foi necessário: o RabbitMQ não aceita redeclarar a fila de solicitação com DLX diferente (`PRECONDITION_FAILED`). As mensagens acumuladas nessa fila se perderam.

### O que falta verificar

- Ainda não há provedor real na porta 8083: o caminho de sucesso (`202`) só foi exercitado com o provedor fake dos testes. O simulador e o Compose completo são da fatia 6C.
- O `docker compose down` é obrigatório para quem já tinha a fila antiga criada (ambientes antigos). Entra no README (fatia 9).
- Os logs de falha do próprio Spring AMQP continuam em inglês, como na fatia 5.

## Fatia 6B — `invoice-webhook` (perfil `webhook`)

### Fontes consultadas

| Item | Fonte |
|---|---|
| Classes de autoconfiguration do Boot 4 a excluir no perfil `webhook` (`DataSource`, transação JDBC, `JdbcClient`, `JdbcTemplate`, health do banco, Hibernate/JPA, repositórios JPA e Flyway) | Listagem dos jars `spring-boot-jdbc`, `spring-boot-hibernate`, `spring-boot-data-jpa` e `spring-boot-flyway` no Maven local (`unzip -l`). No Boot 4 elas moraram em pacotes novos, diferentes do Boot 3 |
| `MessageDigest.isEqual`, `HexFormat` e `Mac` (HmacSHA256) | API padrão do JDK 25 |

### O que a IA gerou

- Testes primeiro, confirmados vermelhos (28 erros de compilação): `EnrichmentResultPublishingServiceTest` (A), `EnrichmentWebhookControllerTest` (W: H1 a H5, ordem das verificações, valor recebido fora da resposta), `SignatureVerifierTest`, `RabbitEnrichmentResultPublisherTest` (RabbitMQ real: corpo idêntico, propriedades, devolução e nack) e `WebhookProfileTest` (H7 e H8, só com o RabbitMQ).
- `EnrichmentResultPublisher` (port), `ResultPublishFailedException`, `EnrichmentResultPublishingService`, `ConfirmedPublisher` (extraído de `RabbitEnrichmentRequestPublisher` e reaproveitado), `RabbitEnrichmentResultPublisher`, `SignatureVerifier`, `EnrichmentResultParser`, `EnrichmentResultRequest`, `EnrichmentWebhookApi`, `EnrichmentWebhookController`, `WebhookExceptionHandler`, `application-webhook.yml` e `RabbitOnlyTestcontainersConfiguration`.
- `@Profile("!webhook")` em 9 beans de banco ou de relay, para o perfil `webhook` não carregar persistência.

### Decisões tomadas com o autor

- O webhook confere o formato da `accessKey` (44 dígitos), além de exigir a presença. Chave fora do formato responde `400` na hora, igual à regra do consumer de resultado.
- `WEBHOOK_HMAC_SECRET` sem valor padrão: sem a variável o perfil não sobe. Os testes definem o próprio segredo.
- Corpo publicado exatamente como chegou (`byte[]`), sem reserializar: a assinatura é sobre os bytes, e o consumer lê o mesmo conteúdo.
- O publisher do resultado preenche `messageId` e `correlationId` nas propriedades AMQP, o que fecha a pendência da fatia 5 (o log `Tentativas esgotadas` vinha sem ids).

### O que foi revisado ou corrigido

- Erro de tipo na primeira compilação: `MessageBuilder.withBody(...)` devolve `MessageBuilderSupport<Message>`, não `MessageBuilder`.
- Os 28 testes do controller falharam na primeira execução por eu ter esquecido de definir o segredo no `@WebMvcTest`. O `SignatureVerifier` recusou subir, que é o comportamento desejado.
- O primeiro `curl` manual devolveu `415` em tudo: o erro era do meu script (aspas dentro da expansão do `-H` quebraram o `Content-Type`), não da aplicação. Refeito com um array de argumentos.
- Corpo ausente passa pela verificação da assinatura antes de virar `400` (`@RequestBody(required = false)`), para a ordem assinatura, payload, publicação valer também para corpo vazio.
- O motivo do `400` traz só o caminho do campo (`payload.amount`); o valor recebido não vai para a resposta nem para o log (há um teste para isso).
- `readFailure` agora existe em três lugares (dois consumers e o parser do webhook), porque ficam em pacotes diferentes e são pequenos. Se uma quarta cópia surgir, vale extrair.

### Verificação executada

- `./mvnw verify`: 204 testes, 0 falhas (41 novos).
- `grep`: `application` não importa `org.springframework.amqp`, `web`, `http`, `adapter` nem `config`; `domain` limpo; os logs do webhook não citam segredo, assinatura, CNPJ ou valor.
- Manual, com o jar no perfil `webhook` contra o RabbitMQ do Compose: o health respondeu `UP` e o log não tem nenhuma menção a Hikari, Flyway, Hibernate ou DataSource. Callback assinado com `openssl` gerou `202` (enviado duas vezes: duas mensagens com o mesmo `messageId` na `invoice.enrichment.result`); corpo adulterado gerou `401`; sem assinatura gerou `401`; `amount` ilegível assinado gerou `400` com `payload.amount`. A Swagger UI abriu e o OpenAPI saiu em português. As duas mensagens de teste foram apagadas da fila e o `worker`, que eu tinha parado para isso, foi religado.

### O que falta verificar

- O fluxo completo (solicitação, provedor, callback, nota `APPROVED`) só existe com o simulador e o Compose da fatia 6C, e H1 e H6 em nível I ficam para a 7.
- Erros genéricos do framework no webhook (por exemplo `405`) saem em `problem+json`, mas com texto padrão em inglês, como na `api`.
- Não há limite de tamanho do corpo além do padrão do servidor; vale olhar na revisão de segurança (fatia 8), junto com replay e SSRF.
- O `invoice-webhook` ainda não tem serviço no Compose nem `WEBHOOK_HMAC_SECRET` no `.env.example` (fatia 6C).

### Reorganização de pacotes depois da revisão do autor

- **Pedido do autor:** a pasta do webhook tinha 8 arquivos soltos e o `application/service`, 12. O autor sugeriu subpastas.
- **Webhook:** `adapter/in/web/webhook` passou a ter `api`, `controller`, `dto`, `exception` e `verification`, como a `api` de notas. O que cruza pacotes virou `public` (interface, DTO, exceções, `SignatureVerifier` e parser); o controller e o handler de erros continuam *package-private*.
- **Service:** `application/service` virou `invoice`, `outbox`, `result` e `provider`, por assunto e não por tipo (uma pasta `exception` teria só uma classe). Os testes foram movidos junto.
- **Feito por script** (`git mv` e ajuste de `package` e `import`) e conferido pela compilação e pelo `verify`: 204 testes, 0 falhas, o mesmo número de antes. Nenhum comportamento mudou.
- **Incidente:** o primeiro `verify` depois do `service` travou por mais de 20 minutos porque o Docker Desktop parou de responder (nem `docker info` voltava). Encerrei o build preso, o autor pediu o reinício completo do Docker, e o `verify` foi refeito do zero. Uma soma de relatórios parciais ("236 testes") apareceu no meio disso e foi descartada por misturar relatórios antigos.

### Reorganização de `port/out` e `adapter/out`

- **Pedido do autor:** olhar `application/port/out` e as pastas `messaging` e `persistence` de `adapter/out`, depois de ter dividido o webhook e o `service`.
- **Decisões:** `port/out` espelha o `adapter/out` (`persistence`, `messaging`, `provider`, `clock`). A persistência é dividida **por entidade** (`invoice`, `outbox`, `processedmessage`) e não por tecnologia, e `messaging` ganhou `request` e `result`, com a base do *publisher confirm* (`ConfirmedPublisher` e `MessagePublishException`) na raiz, agora `public`.
- **JPA e JDBC juntos:** o autor perguntou se a mistura é boa prática. Resposta registrada na spec (seção 9): é um padrão aceito, mas com custo; no `Invoice` o JPA se paga menos, porque as consultas que importam já são escritas à mão. Nada foi migrado. Usar só JDBC fica como evolução possível, a ser feita à parte porque exige reexecutar os testes de concorrência.
- **Feito por script** (`git mv`, `package` e `import`), conferido pela compilação e pelo `verify`. Nenhum comportamento mudou.
- `MessagingTopology` continua em `config/messaging`, importado por adapters (dependência no sentido contrário). Não foi tocado porque o autor não pediu; fica como candidato a refatoração.

## Reformulação antes da fatia 6 — provedor via webhook

Só documentação (`spec.md`, `flow.md`, `checklist.md`, `CLAUDE.md`). Nenhum código, `pom.xml`, Compose ou teste foi alterado, e a fatia 6 não foi implementada.

### Decisões tomadas pelo autor

- O provedor externo não fala por fila: o `worker` o chama por HTTP (resposta `202` sem dados) e o provedor devolve o resultado num **callback assíncrono** a um webhook, com os dados da nota só no callback.
- O webhook é um novo serviço, `invoice-webhook`, com Swagger próprio, como **terceiro perfil (`webhook`)** da mesma imagem de `api` e `worker`, sem banco. O autor escolheu o nome e o perfil depois de comparar com um projeto Maven separado.
- Autenticação do callback por **HMAC do corpo**. Payload inválido recebe `400` e não é publicado.
- O `provider-simulator` continua como projeto separado (provedor externo fake, só HTTP). O autor perguntou se ele ainda era usado; é, porque nada mais responderia à chamada do `worker` nem chamaria o webhook.
- O consumer da solicitação fica no `worker`, com retry e DLQ própria.

### O que a IA gerou

- Contratos novos (2.5, 3.4, 4.8), cenários C1–C4 e H1–H8, fluxogramas 4.1 e 4.2, divisão da fatia 6 em 6A, 6B e 6C e atualização dos demais documentos, sem renumerar seções existentes.

### Defaults assumidos pela IA (a confirmar na implementação)

- Portas 8082 (webhook) e 8083 (simulador); cabeçalho `X-Signature: sha256=<hex>` sem timestamp; 3 reenvios do callback pelo simulador (1s, 2s e 4s); timeouts de 2s e 5s na chamada ao provedor.

### Adaptação do enunciado

- O `DESAFIO.md` diz "o provedor publica o resultado". Agora quem publica é o `invoice-webhook`, em nome do provedor. Registrado na spec (seção 1) e no checklist da fatia 9 para o README.

### O que falta verificar

- Tudo é desenho: nada foi executado. Os cenários C e H, a topologia da DLQ da solicitação e o perfil `webhook` sem datasource só serão exercitados nas fatias 6A a 7.
- O HMAC sem timestamp permite replay; a limitação foi registrada e o efeito é contido pela idempotência do consumer.

## Fatia 6C — Simulador do provedor e Compose

### Fontes consultadas

| Item | Fonte |
|---|---|
| Spring Boot 4.1.2, Java 25, Maven Wrapper 3.10.0, imagens `eclipse-temurin:25-*` | As mesmas versões já confirmadas na fatia 1; o wrapper foi copiado da raiz |
| `RestClient` com `JdkClientHttpRequestFactory`, timeouts e `Redirect.NEVER` | Mesmo padrão do `ProviderHttpClient` (fatia 6A, conferido no bytecode do `spring-web-7.0.8`) |
| HMAC-SHA256 (`Mac`, `HexFormat`) | API padrão do JDK 25, mesmo algoritmo do `SignatureVerifier` (fatia 6B) |

### O que a IA gerou

- Projeto `provider-simulator/` (pacote `dev.matheus.provider`): `pom.xml` (webmvc + actuator + Lombok, sem broker nem banco), wrapper, `Dockerfile`, `.dockerignore`, `application.yml` (porta 8083) e as classes `ProviderSimulatorApplication`, `SimulatorProperties`, `EnrichmentRequest`, `EnrichmentResult` (com o `messageId` derivado e o resultado aprovável), `EnrichmentRequestController`, `RequestDeduplicator`, `HmacSigner` e `WebhookCallbackSender`.
- Serviços `invoice-webhook` e `provider-simulator` no `docker-compose.yml`, `PROVIDER_BASE_URL` no `worker`, as três variáveis novas no `.env.example` (e no `.env` local, com autorização do autor) e `provider-simulator` no `.dockerignore` da raiz.

### Decisões tomadas com o autor

- **Spring Boot mínimo** no simulador, em vez de JDK puro, com pacotes simples (sem a arquitetura hexagonal completa: é um fake de um sistema externo).
- **Dedup sempre** pelo `messageId`, em memória: uma solicitação repetida recebe `202` sem novo callback, mesmo depois de reenvios esgotados. Registrado nas limitações da spec (seção 12).
- **Sem testes automatizados no simulador.** A 6C não tem cenários T/E/C/H; o simulador foi validado só pelo fluxo no Compose (abaixo). Os cenários com provedor de teste ficam na fatia 7.
- **Correção da 6B achada aqui** (ver abaixo): corrigida agora, com teste.

### Decisões de implementação

- O corpo do callback é serializado e assinado **uma vez**; os reenvios mandam os mesmos bytes, então a assinatura e o `messageId` não mudam.
- O atraso e as esperas dos reenvios são agendados num `ScheduledExecutorService` (2 threads), sem `sleep`: o `202` sai na hora e nenhuma thread fica parada.
- `4xx` do webhook e respostas inesperadas (por exemplo `3xx`) não são reenviados, só registrados em log. `5xx`, timeout e conexão recusada são.
- O simulador confere o formato da chave (44 dígitos) e responde `400` se não bater: é o "provedor recusa com `4xx`" que a spec (4.8) espera para chave inválida publicada direto na fila.
- `WEBHOOK_HMAC_SECRET` sem padrão também no simulador, e no Compose com `${WEBHOOK_HMAC_SECRET:?...}`: sem a variável, o `docker compose` falha com mensagem clara em vez de subir com segredo vazio.
- O `worker` agora espera o `provider-simulator` ficar `healthy`, e o simulador espera o `invoice-webhook`, para as primeiras solicitações não caírem na DLQ na subida.
- CNPJ fixo `12ABC34501DE35` (o exemplo da spec, já validado na fatia 2) e `amount` `1234.56`.

### O que foi revisado ou corrigido

- **Bug da 6B: webhook respondia `500` com o RabbitMQ fora**, e não `503` (spec 2.5). Achado ao derrubar o broker durante um callback. O `ConfirmedPublisher` só convertia as falhas de *confirm* em `MessagePublishException`; a `AmqpIOException`/`AmqpConnectException` lançada pelo próprio `rabbitTemplate.send` (conexão recusada, host que não resolve) passava direto e caía no handler genérico. O relay não era afetado, porque o `OutboxPublisherService` captura `RuntimeException`. Teste novo primeiro (`RabbitEnrichmentResultPublisherBrokerDownTest`, porta fechada, confirmado vermelho com `AmqpConnectException`), depois a correção no `ConfirmedPublisher`. No Compose, com o broker parado, o webhook passou a responder `503` com `Retry-After: 5`. Os testes H5 da 6B cobriam `nack`, timeout e devolução, mas não o broker fora.
- O log de reenvio mostrava a espera como `PT1S` (formato ISO do `Duration`); trocado para milissegundos.

### Verificação executada

- `provider-simulator`: `./mvnw package` compila.
- `./mvnw verify` na raiz: 205 testes, 0 falhas (204 + o novo).
- `docker compose down` e `up --build --force-recreate`: os seis serviços `healthy`, na ordem `postgres`/`rabbitmq` → `api`/`invoice-webhook` → `provider-simulator` → `worker`.
- Fluxo completo: `POST /invoices` → `201`, e a nota ficou `APPROVED` cerca de 0,7s depois (300 ms do atraso incluídos), com `cnpj`, `amount`, `issueDate` e `approvedAt`. Logs na ordem: worker (`Solicitação aceita pelo provedor`), simulador (`Solicitação aceita, callback agendado` e `Callback entregue ao webhook`), webhook (`Resultado do provedor publicado`) e worker (`Nota aprovada`).
- `messageId` derivado: o resultado `d4ea6820-…` confere com `UUID.nameUUIDFromBytes("result:b5b95485-…")` calculado no `jshell`.
- Dedup: a mesma solicitação enviada duas vezes direto ao simulador deu dois `202`, um único callback e um único `messageId` novo em `processed_messages` (`Solicitação duplicada ignorada` no log).
- `400` em `problem+json` para corpo ausente, JSON inválido, `messageId`, `payload.invoiceId` ou `payload.accessKey` ausentes, chave fora do formato e `messageId` que não é UUID, sempre só com o caminho do campo.
- Reenvios com o webhook parado: esperas de 1s, 2s e 4s e `Reenvios do callback esgotados` cerca de 7s depois; a nota ficou `IN_ANALYSIS` (limitação esperada).
- Reenvio em `5xx` com recuperação: simulador temporário com esperas de 5s, 10s e 20s e o RabbitMQ parado; dois `5xx` e entrega no 3º envio depois de o broker voltar. (Esse teste foi feito antes da correção, por isso o status era `500`.)
- No fim, as quatro filas e DLQs com 0 mensagens, e nenhuma ocorrência de CNPJ, valor, segredo ou assinatura nos logs do `worker`, do webhook e do simulador.

### O que falta verificar

- O simulador não tem testes automatizados (decisão do autor). Os cenários C1, C3, H1 e H6 em nível I, com provedor de teste, são da fatia 7.
- Reenvios do callback esgotados deixam a nota em `IN_ANALYSIS`, e a dedup impede que uma reentrega da solicitação gere o callback de novo.
- A rejeição pelo fluxo completo não é exercitada no Compose: o simulador só gera resultados aprováveis (spec, seção 6).

### Reorganização de pacotes do simulador depois da revisão do autor

- **Pedido do autor:** o pacote `dev.matheus.provider` tinha 9 classes soltas.
- **Decisões:** separação **por camada** (o autor escolheu entre "por assunto" e "por camada") e **só mover**, sem dividir o `WebhookCallbackSender` em duas classes (menos risco, já que o simulador não tem testes automatizados).
- **Estrutura:** `config` (`SimulatorProperties`), `controller` (`EnrichmentRequestController`), `model` (`EnrichmentRequest`, `EnrichmentResult`) e `service` (`RequestDeduplicator`, `HmacSigner`, `WebhookCallbackSender`). A `ProviderSimulatorApplication` fica na raiz, e o `@ConfigurationPropertiesScan` continua achando a `SimulatorProperties` nos subpacotes.
- **Visibilidade:** o que passou a cruzar pacotes virou `public` (os dois records e seus métodos usados fora, as três classes de `service` e seus métodos de entrada). O controller continua *package-private*.
- **Verificação:** `./mvnw package` do simulador compila; no Compose, o simulador reconstruído manteve o fluxo (nota `APPROVED` com o CNPJ esperado) e o `400` para corpo inválido. Nenhum comportamento mudou.

## Fatia 7 — Integração com o broker real

### O que a IA gerou

- Pacote de testes `src/test/java/dev/matheus/invoice/integration/`, dividido por assunto como o `main` (`invoice/`, `outbox/`, `result/`, `webhook/`) e com o apoio em `support/` (classes `public`, já que cruzam pacotes): a base `IntegrationTestSupport` (um par de containers PostgreSQL e RabbitMQ por JVM, compartilhado, e a tag `integration`), o `FakeProvider` (servidor HTTP do JDK que imita o simulador: `202`, depois callback assinado com o `messageId` derivado), o `WebhookApp` (2º contexto Spring com o perfil `webhook`) e seis classes de teste.
- Perfis Maven `integration` e `unit` no `pom.xml`. Sem perfil, o build roda tudo, como antes.
- Nenhuma mudança em código de produção.

### Decisões tomadas com o autor

- **Webhook em um 2º contexto** dentro do teste: o perfil `webhook` não convive com `api`/`worker` (sem datasource, beans diferentes).
- **Testes de integração em pacote próprio, com comando separado** (`-Pintegration`), mas também rodando junto no `./mvnw verify`.
- **T9 com `docker pause` num RabbitMQ só do teste.** O autor perguntou se parar o broker não atrapalharia os outros testes; por isso o broker compartilhado nunca é pausado.

### Achados e correções durante a fatia

- **O 2º contexto não pode usar a `InvoiceApplication`.** Fora do `@SpringBootTest` não há o filtro que exclui as `@TestConfiguration` do component scan, que subiriam containers sozinhas. O `WebhookApp` tem uma configuração própria, com exclusão explícita.
- **Propriedades do 2º contexto como argumentos de linha de comando.** Com `.properties(...)` (propriedades padrão, menor precedência), o `application-webhook.yml` (`hmac-secret: ${WEBHOOK_HMAC_SECRET:}`) sobrescrevia o segredo com vazio e o contexto não subia.
- **Broker pausado e canais novos.** O timeout de conexão não cobre abrir um canal numa conexão já aberta (o padrão da espera é 10 minutos); o T9 define `spring.rabbitmq.channel-rpc-timeout=1s`.
- **Falha intermitente (1 em ~7 execuções) no T1,** que não era do T1: o `@AfterAll` do T9 parava o RabbitMQ antes de o Spring fechar o contexto (`@DirtiesContext`), e o erro de conexão numa thread solta era atribuído ao teste seguinte pelo Awaitility (que captura exceções não tratadas de qualquer thread). Corrigido deixando o Testcontainers remover o broker no fim da JVM. Depois disso, 8 execuções seguidas sem falha.
- **Contextos com worker são descartados no fim da classe** (`@DirtiesContext`): o broker é compartilhado, e um worker esquecido em cache disputaria as filas com as outras classes.

### Verificação executada

- `./mvnw verify`: 215 testes, 0 falhas (205 anteriores + 10 novos).
- `./mvnw -Pintegration test`: 10 testes em 6 classes, 8 execuções seguidas sem falha depois da correção acima. `./mvnw -Punit test`: 205 testes. Nenhum container de teste sobrou depois das execuções.

### O que falta verificar

- **T9 simula a falha com o broker pausado, não derrubado.** A conexão aberta continua de pé, então a publicação falha por falta de *confirm* (e por timeout ao abrir canal), e não por conexão recusada (essa é coberta por `RabbitEnrichmentResultPublisherBrokerDownTest`). Como o broker processa o que ficou no *socket* ao despausar, o provedor pode receber a solicitação duas vezes; o teste confere que todas têm o mesmo `messageId` (entrega *at-least-once*).
- **C3 em nível I usa `503` do provedor.** A falta de resposta (timeout) continua coberta em `EnrichmentRequestConsumerTest`.
- O `messageId` da solicitação ser o `id` da linha do outbox só é conferido de ponta a ponta no T9, porque a linha é apagada depois da publicação.

### Notas para o README (como a reentrega foi provocada)

Chamar a função de processamento duas vezes não prova reentrega: ela precisa vir do RabbitMQ. Duas formas, no pacote `integration`:

1. **Consumidor de teste (`BrokerRedeliveryIntegrationTest`).** Um consumidor com `ack` manual recebe o resultado e fecha o canal **sem confirmar**. O RabbitMQ devolve a mensagem à fila e a entrega de novo com `redelivered = true`, que o teste confere. Prova o comportamento do broker, sem consumer real.
2. **Consumer real (`ResultRedeliveryIntegrationTest`).** Um interceptador só de testes é colocado **fora** do retry do consumer (primeiro da cadeia de advices da `resultListenerContainerFactory`). Ele deixa o consumer real processar e dar commit na 1ª entrega e, depois, lança `ImmediateRequeueAmqpException`, que faz o container dar `nack` com requeue. O broker reentrega com `redelivered = true`; na 2ª entrega o consumer vê o `messageId` em `processed_messages`, não altera nada e dá `ack`. O teste confere a nota atualizada uma única vez (mesmo `approvedAt` nas duas entregas), uma linha em `processed_messages` e a DLQ vazia. Dentro do retry, a exceção reexecutaria o processamento em vez de devolver a mensagem ao broker, por isso o interceptador fica por fora.

## Fatia 8 — Validação e revisão

### Revisão de segurança

- **O que a IA gerou:** o agente `security-reviewer` (`.claude/agents/security-reviewer.md`) revisou o código só com leitura e gerou um relatório com 0 achados altos, 2 médios, 5 baixos e 7 informativos. Os médios: Postgres, AMQP e o painel do RabbitMQ publicados em `0.0.0.0`, e o corpo do webhook lido inteiro na memória antes do HMAC.
- **Corrigido:** o corpo do webhook. O controller passou a receber o `InputStream` e o `LimitedBodyReader` lê no máximo `limite + 1` bytes (`invoice.webhook.max-body-bytes`, 64 KB). Acima disso, responde `413` (`problem+json`) antes da assinatura. A spec (2.5, cenário H9), o `flow.md` (4.1) e o `CLAUDE.md` foram atualizados com a nova ordem: tamanho, assinatura, payload e publicação.
- **Testes:** H9 em nível W (corpo acima do limite assinado corretamente dá `413`, o tamanho é conferido antes da assinatura, corpo no limite segue para as próximas checagens) e no servidor real (`WebhookProfileTest`, corpo de 128 KB em *chunked*, sem `Content-Length`).
- **Revisado pelo autor:** o autor confirmou o cenário (POST grande sem segredo derruba o `invoice-webhook`) e pediu a correção. Perguntou se trocar a senha do `.env.example` resolveria o achado das portas. Resposta: ajuda, mas as portas continuam expostas à rede. A correção é publicá-las só em `127.0.0.1`.
- **Corrigido também:** portas do Compose publicadas só em `127.0.0.1` (achados 1 e 6).
- **Mesmo risco nos outros endpoints:** `POST /invoices` e `POST /enrichment-requests` (simulador) também liam o corpo sem teto. Corrigido com um `BodySizeLimitFilter` em cada projeto (`413` acima de 8 KB, `Content-Length` conferido sem ler e *chunked* lido até `limite + 1` bytes). Sem testes automatizados novos, a pedido do autor. Verificado à mão com `curl` contra a `api` real (Postgres e RabbitMQ do Compose) e contra o simulador: corpo válido (`201`, `200` na repetição, `202`), 200 KB com `Content-Length` e em *chunked* (`413`), 1 GB declarado sem enviar (`413` na hora), chave inválida (`400`) e Swagger (`200`).
- **Achado 3 corrigido (nota presa em `IN_ANALYSIS`):** a rejeição gravava o `cnpj` e o `amount` recebidos; se não coubessem na coluna (`cnpj` acima de 14 caracteres, `amount` acima de `NUMERIC(15,2)`), o `UPDATE` falhava e a mensagem ia para a DLQ após 3 retries. Os dois testes de persistência escritos antes da correção reproduziram o erro. Agora `Invoice` grava na rejeição só o que cabe (o resto fica nulo) e trata `amount` acima de 13 dígitos inteiros como dado inválido ("amount acima do limite"), o que também evita **aprovar** uma nota que não poderia ser gravada. O limite é calculado por `precision - scale`, sem expandir o número, para que `1E+1000000000` não gaste memória. Decisão do autor: tratar as duas partes. Spec (4.2, 4.4, E2), `flow.md` e `CLAUDE.md` atualizados.
- **Decidido pelo autor:** os achados 4 (senhas padrão), 5 (`3xx` do provedor) e 7 (segredo de exemplo fraco) ficam como estão: o `.env` é o que importa nesse contexto. Registrar no README como limitações.
- **Informativos registrados como limitações (README):** imagens sem digest, usuário único do RabbitMQ, `415` antes de `401`, `amount` com mais de 2 casas arredondado, Maven Wrapper sem checksum. `.claude/agents/` fica versionado, como evidência do uso de IA.

### Revisão de boas práticas

- O agente `software-architect` (`.claude/agents/software-architect.md`) foi executado pelo autor em outro chat, e as correções também foram feitas pelo autor lá. Neste chat o item foi só confirmado, sem nova execução.

### Aderência à spec

- **O que a IA fez:** cruzou todos os cenários da seção 7 (T1–T10, E1–E10, C1–C4, H1–H9) com os métodos de teste existentes. Todos têm teste no nível pedido, sem lacunas.
- **Build final:** `./mvnw verify` com 228 testes, 0 falhas, `BUILD SUCCESS`; o simulador compila com `./mvnw package`.
- **Para verificar:** a rastreabilidade é por nome de teste. T2, T4–T6, T10 e E1–E3, E6, E7, E9, E10 não levam o ID no nome, então a conferência foi semântica.
