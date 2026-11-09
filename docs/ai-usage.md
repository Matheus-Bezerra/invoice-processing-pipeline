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
- O log `Tentativas esgotadas` usa o `messageId`/`correlationId` das propriedades AMQP, que vieram vazias na publicação manual. O simulador (fatia 6) deve preencher essas propriedades, como o publisher da solicitação já faz.
- Os logs de falha do listener do próprio Spring AMQP (`Execution of Rabbit message listener failed`) saem em inglês e com stack trace; só as mensagens da aplicação estão em português.

### Ajustes depois da revisão do autor

- **Espera por conexão do pool do banco de 30s para 5s** (`spring.datasource.hikari.connection-timeout`, variável `DB_CONNECTION_TIMEOUT`, em milissegundos porque o Hikari recebe um `long`; vale para api e worker). O autor perguntou se 5s não cortaria consultas lentas. Não corta: o valor limita só a espera para **pegar** uma conexão do pool, e não a duração da consulta. Com conexões livres, pegar uma leva microssegundos; a espera só cresce com o banco fora (sem chance de sucesso) ou com o pool esgotado (no worker são 2 threads para 10 conexões). Adicionado à tabela de parâmetros da spec (seção 8).
- E5 repetido com `docker compose stop postgres`: a 1ª execução falhou na hora (17:52:05, conexão derrubada pelo desligamento) e as outras às 17:52:12, 17:52:21 e 17:52:34 (esperas de 2s, 4s e 8s + 5s do pool). DLQ em **29s, contra 134s antes**. Um `POST` com o banco fora devolveu `500` em 5,1s.
- `./mvnw verify` depois do ajuste: 134 testes, 0 falhas.
