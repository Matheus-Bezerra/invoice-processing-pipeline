# CLAUDE.md

Orientações para quem for implementar este projeto. Leia antes de qualquer tarefa.

## O que é

**FiscalFlow** (artefato Maven `fiscal-flow`; o pacote continua `dev.matheus.invoice`). Desafio de estudo: serviço que recebe a chave de acesso (44 dígitos) de uma nota fiscal, grava a nota em `IN_ANALYSIS`, pede o enriquecimento por mensageria (outbox + RabbitMQ) e, ao receber o resultado de um provedor simulado, aprova ou rejeita a nota. O provedor fala HTTP: o `worker` repassa a solicitação por `POST`, o provedor responde `202` e devolve o resultado chamando um **webhook assinado (HMAC)**, que publica o resultado na fila.

## Leia primeiro

1. [docs/spec.md](docs/spec.md): contratos HTTP e de mensagens, regras, cenários de teste e plano em fatias. **É a fonte da verdade.**
2. [docs/flow.md](docs/flow.md): diagramas do fluxo (Mermaid).
3. [DESAFIO.md](DESAFIO.md): enunciado original.
4. [docs/checklist.md](docs/checklist.md): acompanhamento do que já foi feito.

Se a spec e o código divergirem, a spec manda. Se uma decisão precisar mudar, atualize a spec e o `flow.md` na mesma tarefa e avise.

## Idioma

- **Código em inglês**: classes, métodos, campos, enums, rotas, nomes de filas, tabelas e colunas.
- **Português**: README, `docs/` e **toda mensagem voltada a pessoas**, de sucesso ou de erro:
  - motivos de rejeição;
  - `title` e `detail` dos erros da API e mensagens de validação (Bean Validation);
  - textos do Swagger (`summary`, `description` e descrições das respostas, inclusive as de sucesso);
  - mensagens de log (por exemplo `Nota aprovada`, `Resultado duplicado ignorado`).
- Identificadores técnicos (campos JSON, enums, nomes de filas) não são traduzidos.
- Horários em **UTC** (`timestamptz` no banco, ISO-8601 na API).

## Stack

Java 25 LTS, Spring Boot (linha indicada pela skill, confirmada na documentação oficial), Maven Wrapper, PostgreSQL, RabbitMQ, Flyway, Testcontainers, Lombok, Swagger/OpenAPI (springdoc), Docker Compose.

- Não invente versões. Confirme o patch estável na documentação oficial antes de gerar o `pom.xml`.
- Se algo for incompatível (Spring Boot, springdoc, Java), avise e peça aprovação antes de reduzir versões.
- Sem recursos preview ou experimentais do Java.

## Arquitetura

Baseada na skill `spring-hexagonal-architecture` (em `../spring-hexagonal-architecture`), **adaptada**. A skill é referência. Em caso de conflito, vale este arquivo.

Pacote base: `dev.matheus.invoice`.

```text
dev.matheus.invoice/
├── domain/            Invoice, InvoiceStatus, AccessKey, Cnpj (só validador), exceções de negócio
├── application/
│   ├── service/       um subpacote por assunto: invoice/ (criação e consulta de notas),
│   │                  outbox/ (relay), result/ (processamento do resultado) e
│   │                  provider/ (repasse da solicitação ao provedor, publicação do resultado do webhook)
│   └── port/out/      espelha adapter/out: persistence/ (invoice/, outbox/, processedmessage/),
│                      messaging/ (EnrichmentRequestPublisher, EnrichmentResultPublisher),
│                      provider/ (ProviderClient) e clock/ (ClockPort), cada port com as suas exceções
├── adapter/
│   ├── in/web/        api/ (interface com Swagger), controller/, dto/, exception/,
│   │                  webhook/ com os mesmos api/, controller/, dto/, exception/ e verification/ (HMAC e parser)
│   ├── in/messaging/  consumers do resultado e da solicitação
│   ├── in/scheduler/  job do relay
│   └── out/           persistence/ por entidade: invoice/ (JPA), outbox/ e processedmessage/ (JDBC);
│                      messaging/ (request/ e result/, publishers RabbitMQ), http/ (client do provedor), clock/
├── config/
└── shared/            só se houver algo realmente compartilhado
```

Regra de dependência: `adapter/in → application → domain`. `adapter/out → application.port + domain`. O `domain` não importa Spring, JPA, RabbitMQ nem HTTP. O `application` não conhece controllers, DTOs nem `RabbitTemplate`.

### O que usar da skill

- Camadas, ports de saída e adapters.
- Comportamento dentro de `Invoice` (`approve`, `reject`), com mutação de estado só por esses métodos. Value object `AccessKey` e validador `Cnpj` (a nota guarda o CNPJ como `String`, pois rejeições gravam o valor inválido).
- `Invoice` separada da entidade JPA, com **mapper manual**.
- Controller pequeno que implementa uma interface `InvoiceApi` em `adapter/in/web/api`, onde ficam as anotações Swagger/OpenAPI. Swagger UI ativa na `api` para testar à mão.
- Flyway para migrações. Testcontainers para PostgreSQL e RabbitMQ.
- Lombok:
  - `@RequiredArgsConstructor` em services e adapters (sem `@Autowired` em campo);
  - `@Slf4j` para logs;
  - domínio: `@Getter` + `@Accessors(fluent = true)`, **sem** `@Data` nem setters;
  - entidades JPA: `@Data` é aceitável.

### O que NÃO usar da skill

`port/in`, MapStruct, eventos de domínio com `pullEvents`, envelope completo (`aggregateVersion`, `causationId`), Micrometer, OpenTelemetry, Pact, `domain/service`, `config/security`. Observabilidade se limita a logs em `stdout`, `correlationId` e health check do Actuator.

Para `outbox` e `processed_messages` **não** crie modelo de domínio: use adapters simples de persistência (JPA com query nativa ou `JdbcClient`, o que for mais claro para `FOR UPDATE SKIP LOCKED`).

## Decisões fixas (não rediscutir sem motivo)

- **Outbox:** nota + linha do outbox na mesma transação. A linha é apagada na transação que confirma a publicação (sem `published_at`). `id` da linha = `messageId` da solicitação.
- **Publicação:** imediata após o commit na `api` e relay agendado no `worker` (tick 5s, lote de 50, backoff `min(2^attempts s, 60s)`, sem limite de tentativas). Ambos usam a mesma função, com `FOR UPDATE SKIP LOCKED` e *publisher confirm*. Entrega *at-least-once*.
- **A API nunca chama o provedor.** Só publica na fila. O request não espera o enriquecimento e nunca aprova a nota. Quem chama o provedor por HTTP é o consumer da solicitação, no `worker`, de forma assíncrona.
- **Criação:** `201` para chave nova, `200` com a nota existente para chave repetida. A garantia vem do `UNIQUE`, usando `INSERT ... ON CONFLICT DO NOTHING` e releitura. Chave com `trim` e exatamente 44 dígitos, senão `400`.
- **Resultado:** a nota é localizada por `accessKey`. O resultado nunca cria nota.
- **Decisão (ordem):** `cancelled = true` → rejeita; falta `cnpj`, `amount`, `issueDate` ou `cancelled` → rejeita como incompleta; CNPJ inválido, `amount` negativo ou acima de 13 dígitos inteiros + 2 decimais (limite da coluna) → rejeita como dados inválidos; senão aprova. `cancelled` é lido como `Boolean`, **nunca** `boolean`.
- **CNPJ:** alfanumérico aceito, 14 caracteres, sem máscara, maiúsculas, dígitos verificadores conforme documentação oficial da Receita.
- **Idempotência:** `processed_messages(message_id)` gravada na **mesma transação** do update, mais guarda de estado final (`UPDATE ... WHERE status = 'IN_ANALYSIS'`). Trava a linha da nota com `FOR UPDATE`.
- **Destino das mensagens:** malformada e chave inexistente → DLQ direto; duplicata e nota finalizada → `ack` com log; erro técnico → 3 retries (2s, 4s, 8s) dentro do worker e depois DLQ. `prefetch = 1`.
- **`messageId` do resultado** é derivado do `messageId` da solicitação (`UUID.nameUUIDFromBytes`), no simulador.
- **Provedor via HTTP, não via fila:** o `worker` consome `invoice.enrichment.request` e faz `POST {PROVIDER_BASE_URL}/enrichment-requests` (resposta `202` sem dados). Os dados da nota chegam **só** no callback. `4xx` e malformada → DLQ da solicitação (`invoice.enrichment.request.dlq`) direto; `5xx`, `3xx` (redirecionamento não seguido) e timeout → 3 retries (2s, 4s, 8s) e DLQ. A URL do callback é configuração do provedor e nunca vai na mensagem.
- **Webhook** (`invoice-webhook`, perfil `webhook`): `POST /webhooks/enrichment-results`, corpo = envelope `EnrichmentResult`, cabeçalho `X-Signature: sha256=<hex>` (HMAC-SHA256 do corpo bruto, comparação em tempo constante, segredo `WEBHOOK_HMAC_SECRET`). Ordem: tamanho do corpo (`413` acima de 64 KB, lido com teto), assinatura (`401`), payload (`400`), publicação com *publisher confirm* (`202`, ou `503` se falhar). Não acessa banco e não valida regra de negócio. Tem Swagger próprio. Nunca loga o segredo nem a assinatura.
- **Simulador** (`provider-simulator/`, projeto separado, só HTTP, sem broker): recebe a chamada, responde `202`, espera, assina o resultado completo e aprovável e chama o webhook (até 3 reenvios em `5xx`). Rejeições são cobertas só nos testes automatizados.
- `api`, `worker` e `invoice-webhook` são o mesmo código e a mesma imagem, com perfis `api`, `worker` e `webhook` do Spring. O perfil `webhook` não usa banco (sem DataSource, JPA nem Flyway).

## Comandos

Requisitos: JDK 25 e Docker em execução (os testes usam Testcontainers).

```bash
./mvnw verify                      # compila e roda os testes (Testcontainers sobe Postgres e RabbitMQ)
./mvnw test                        # só os testes (unitários e de integração)
./mvnw test -Pintegration          # só os de integração (pacote integration, tag "integration"; RabbitMQ e PostgreSQL reais)
./mvnw test -Punit                 # tudo, menos os de integração
cp .env.example .env               # uma vez; valores locais de exemplo
docker compose up --build          # postgres, rabbitmq, api (8080), worker (8081), invoice-webhook (8082) e provider-simulator (8083)
docker compose down                # derruba tudo
```

Todas as variáveis do `.env.example` são obrigatórias: o Compose usa `${VAR:?mensagem}` e, se alguma faltar ou estiver vazia, para antes de criar os containers e diz qual é. Ao acrescentar uma variável ao Compose, use a mesma sintaxe e inclua-a no `.env.example`.

O simulador é um projeto Maven separado, com wrapper próprio:

```bash
cd provider-simulator && ./mvnw package                    # compila o simulador (não tem testes automatizados)
WEBHOOK_HMAC_SECRET=segredo-local ./mvnw spring-boot:run   # simulador em :8083, chama o webhook em WEBHOOK_URL (padrão http://localhost:8082)
```

Rodar localmente fora do Compose (com `postgres` e `rabbitmq` do Compose no ar):

```bash
docker compose up -d postgres rabbitmq
SPRING_PROFILES_ACTIVE=api ./mvnw spring-boot:run      # API em :8080, Swagger em /swagger-ui.html
SPRING_PROFILES_ACTIVE=worker ./mvnw spring-boot:run   # worker, health em :8081/actuator/health
WEBHOOK_HMAC_SECRET=segredo-local SPRING_PROFILES_ACTIVE=webhook ./mvnw spring-boot:run  # webhook em :8082, Swagger em /swagger-ui.html (só precisa do rabbitmq; o segredo é obrigatório e não tem padrão)
```

Health check: `curl localhost:8080/actuator/health` (api), `curl localhost:8081/actuator/health` (worker) e `curl localhost:8082/actuator/health` (invoice-webhook).

## Como trabalhar

- Siga o plano em fatias da spec (seção 10), **uma fatia por vez**, sem adiantar a próxima.
- Ao concluir um item, marque `[x]` em `docs/checklist.md`, **só depois** de o código existir e os testes passarem. Se um item não puder ser concluído, deixe desmarcado e explique o motivo.
- Em cada fatia, escreva primeiro os testes dos cenários indicados (T1–T10, E1–E10, C1–C4, H1–H8) e termine com eles passando.
- A fatia 6 é dividida em 6A (chamada ao provedor), 6B (`invoice-webhook`) e 6C (simulador e Compose). Siga nessa ordem.
- Rode o build e os testes antes de dizer que terminou, e diga o que passou e o que falhou.
- Não crie pacotes, interfaces ou classes sem uso. Prefira o simples.
- Mantenha o simulador, o Compose e o `.env.example` funcionando: o projeto precisa subir com `docker compose up` sem contas externas.
- **Não versione segredos.** Use `.env.example` com valores locais de exemplo.
- **Não faça commit nem push sem pedido explícito.**
- Teste de reentrega: chamar a função de processamento duas vezes **não** demonstra reentrega pelo broker. A reentrega precisa vir do RabbitMQ (canal fechado sem `ack` ou `nack` com requeue).

## Registro de uso de IA

O desafio exige registrar o que a IA gerou e o que foi revisado ou corrigido. Mantenha `docs/ai-usage.md`, criado na fatia 1, e atualize ao fim de cada fatia com: o que foi gerado, o que foi revisado ou corrigido pelo autor e o que ficou para verificar. O resumo vai para o README na fatia 9.

