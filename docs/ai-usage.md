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
