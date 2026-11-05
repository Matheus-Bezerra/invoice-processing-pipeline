# CLAUDE.md

Orientações para quem for implementar este projeto. Leia antes de qualquer tarefa.

## O que é

Desafio de estudo: serviço que recebe a chave de acesso (44 dígitos) de uma nota fiscal, grava a nota em `IN_ANALYSIS`, pede o enriquecimento por mensageria (outbox + RabbitMQ) e, ao receber o resultado de um provedor simulado, aprova ou rejeita a nota.

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
├── domain/            Invoice, InvoiceStatus, AccessKey, Cnpj, exceções de negócio
├── application/
│   ├── service/       criação/consulta de notas, relay do outbox, processamento do resultado
│   └── port/out/      InvoiceRepository, OutboxPort, EnrichmentRequestPublisher,
│                      ProcessedMessageStore, ClockPort
├── adapter/
│   ├── in/web/        api/ (interface com Swagger), controller/, dto/, exception/
│   ├── in/messaging/  consumer do resultado
│   ├── in/scheduler/  job do relay
│   └── out/           persistence/ (JPA), messaging/ (publisher RabbitMQ)
├── config/
└── shared/            só se houver algo realmente compartilhado
```

Regra de dependência: `adapter/in → application → domain`. `adapter/out → application.port + domain`. O `domain` não importa Spring, JPA, RabbitMQ nem HTTP. O `application` não conhece controllers, DTOs nem `RabbitTemplate`.

### O que usar da skill

- Camadas, ports de saída e adapters.
- Comportamento dentro de `Invoice` (`approve`, `reject`), com mutação de estado só por esses métodos. Value objects `AccessKey` e `Cnpj`.
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
- **Publicação:** imediata após o commit na `api` e relay agendado no `worker` (tick 1s, lote de 50, backoff `min(2^attempts s, 60s)`, sem limite de tentativas). Ambos usam a mesma função, com `FOR UPDATE SKIP LOCKED` e *publisher confirm*. Entrega *at-least-once*.
- **A API nunca chama o provedor.** Só publica na fila. O request não espera o enriquecimento e nunca aprova a nota.
- **Criação:** `201` para chave nova, `200` com a nota existente para chave repetida. A garantia vem do `UNIQUE`, usando `INSERT ... ON CONFLICT DO NOTHING` e releitura. Chave com `trim` e exatamente 44 dígitos, senão `400`.
- **Resultado:** a nota é localizada por `accessKey`. O resultado nunca cria nota.
- **Decisão (ordem):** `cancelled = true` → rejeita; falta `cnpj`, `amount`, `issueDate` ou `cancelled` → rejeita como incompleta; CNPJ inválido ou `amount` negativo → rejeita como dados inválidos; senão aprova. `cancelled` é lido como `Boolean`, **nunca** `boolean`.
- **CNPJ:** alfanumérico aceito, 14 caracteres, sem máscara, maiúsculas, dígitos verificadores conforme documentação oficial da Receita.
- **Idempotência:** `processed_messages(message_id)` gravada na **mesma transação** do update, mais guarda de estado final (`UPDATE ... WHERE status = 'IN_ANALYSIS'`). Trava a linha da nota com `FOR UPDATE`.
- **Destino das mensagens:** malformada e chave inexistente → DLQ direto; duplicata e nota finalizada → `ack` com log; erro técnico → 3 retries (2s, 4s, 8s) dentro do worker e depois DLQ. `prefetch = 1`.
- **`messageId` do resultado** é derivado do `messageId` da solicitação (`UUID.nameUUIDFromBytes`), no simulador.
- **Simulador** (`provider-simulator/`, projeto separado) sempre devolve resultado completo e aprovável. Rejeições são cobertas só nos testes automatizados.
- `api` e `worker` são o mesmo código e a mesma imagem, com perfis `api` e `worker` do Spring.

## Comandos

Requisitos: JDK 25 e Docker em execução (os testes usam Testcontainers).

```bash
./mvnw verify                      # compila e roda os testes (Testcontainers sobe Postgres e RabbitMQ)
./mvnw test                        # só os testes
cp .env.example .env               # uma vez; valores locais de exemplo
docker compose up --build          # postgres, rabbitmq, api (8080) e worker (8081)
docker compose down                # derruba tudo
```

Rodar localmente fora do Compose (com `postgres` e `rabbitmq` do Compose no ar):

```bash
docker compose up -d postgres rabbitmq
SPRING_PROFILES_ACTIVE=api ./mvnw spring-boot:run      # API em :8080, Swagger em /swagger-ui.html
SPRING_PROFILES_ACTIVE=worker ./mvnw spring-boot:run   # worker, health em :8081/actuator/health
```

Health check: `curl localhost:8080/actuator/health` (api) e `curl localhost:8081/actuator/health` (worker).

## Como trabalhar

- Siga o plano em fatias da spec (seção 10), **uma fatia por vez**, sem adiantar a próxima.
- Ao concluir um item, marque `[x]` em `docs/checklist.md`, **só depois** de o código existir e os testes passarem. Se um item não puder ser concluído, deixe desmarcado e explique o motivo.
- Em cada fatia, escreva primeiro os testes dos cenários indicados (T1–T10, E1–E10) e termine com eles passando.
- Rode o build e os testes antes de dizer que terminou, e diga o que passou e o que falhou.
- Não crie pacotes, interfaces ou classes sem uso. Prefira o simples.
- Mantenha o simulador, o Compose e o `.env.example` funcionando: o projeto precisa subir com `docker compose up` sem contas externas.
- **Não versione segredos.** Use `.env.example` com valores locais de exemplo.
- **Não faça commit nem push sem pedido explícito.**
- Teste de reentrega: chamar a função de processamento duas vezes **não** demonstra reentrega pelo broker. A reentrega precisa vir do RabbitMQ (canal fechado sem `ack` ou `nack` com requeue).

## Registro de uso de IA

O desafio exige registrar o que a IA gerou e o que foi revisado ou corrigido. Mantenha `docs/ai-usage.md`, criado na fatia 1, e atualize ao fim de cada fatia com: o que foi gerado, o que foi revisado ou corrigido pelo autor e o que ficou para verificar. O resumo vai para o README na fatia 8.

