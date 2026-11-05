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
