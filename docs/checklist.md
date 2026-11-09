# Checklist de implementação

Acompanhamento do que a [spec](spec.md) pede. Marque `[x]` **só depois** de o item estar implementado e os testes correspondentes estarem passando. Os IDs (T1, E4 etc.) são os cenários da seção 7 da spec.

Níveis dos testes: **D** domínio, **A** serviço de aplicação, **W** HTTP (MockMvc), **P** persistência (Testcontainers), **I** integração com o broker real.

## Fatia 1 — Projeto e infraestrutura

- [x] Versões confirmadas na documentação oficial (Spring Boot, springdoc, Testcontainers)
- [x] Projeto Maven com Java 25, Maven Wrapper, Lombok, Flyway, Actuator e springdoc
- [x] Migrações Flyway: `invoices`, `outbox`, `processed_messages`
- [x] `docker-compose.yml` com `postgres`, `rabbitmq`, `api` e `worker`, com healthchecks
- [x] `.env.example` com valores locais de exemplo (sem segredos)
- [x] `api` e `worker` sobem com os perfis certos e o health check responde
- [x] `docs/ai-usage.md` criado
- [x] Seção "Comandos" do `CLAUDE.md` preenchida

## Fatia 2 — Domínio

- [x] `AccessKey` (44 dígitos) e `Cnpj` (alfanumérico, dígitos verificadores conferidos na documentação oficial)
- [x] `Invoice` com `approve` e `reject`, sem setters públicos
- [x] **T3** (D; nível A na fatia 5): resultado completo e não cancelado aprova, com dados e `approvedAt`
- [x] **T4** (D; nível A na fatia 5): resultado cancelado rejeita, com motivo e sem `approvedAt`
- [x] **T5** (D; nível A na fatia 5): falta de `cnpj`, `amount` ou `issueDate` rejeita como incompleta (um teste por campo)
- [x] **E1** (D): `cancelled` ausente rejeita como incompleta
- [x] **E2** (D): CNPJ inválido ou `amount` negativo rejeita como dados inválidos
- [x] **E10** (D): cancelada e incompleta ao mesmo tempo gera o motivo de cancelamento
- [x] Nota finalizada não muda com um novo resultado

## Fatia 3 — API e outbox

- [x] `POST /invoices` grava a nota e a linha do outbox na mesma transação
- [x] `GET /invoices/{id}` e `GET /invoices?accessKey=`
- [x] Interface `InvoiceApi` com Swagger em português e Swagger UI funcionando
- [x] Erros em `problem+json`, com `title` e `detail` em português
- [x] **T1** (W): chave válida cria a nota em `IN_ANALYSIS`, sem `approvedAt`
- [x] **T2** (A): a nota continua `IN_ANALYSIS` enquanto não houver resultado
- [x] **T6** (P, W): duas requisições com a mesma chave, inclusive simultâneas, mantêm uma só nota
- [x] **T10** (W): chave com formato inválido recebe `400`
- [x] **E6** (W): chave com espaços nas pontas é aceita como a mesma chave
- [x] **E7** (W, P): chave repetida devolve `200` com o mesmo `id` e não cria outra linha no outbox

## Fatia 4 — Relay e publisher

- [x] Topologia do RabbitMQ (exchange, filas, DLX, DLQ) declarada
- [x] Publisher com *publisher confirm*
- [x] Publicação imediata após o commit na `api`
- [x] Relay agendado no `worker` com `FOR UPDATE SKIP LOCKED`, lote e backoff
- [x] **T9** (A): falha na publicação preserva a nota, e o relay publica depois
- [x] **E8** (P): a linha do outbox é apagada depois da publicação confirmada
- [x] **E9** (P): dois relays simultâneos não publicam a mesma linha duas vezes

## Fatia 5 — Consumer do resultado

- [x] Consumer com transação, `FOR UPDATE` na nota e `processed_messages`
- [x] Retry (3 retries, 2s, 4s e 8s) e DLQ configurados
- [x] **T7** (A): o mesmo resultado entregue duas vezes não atualiza de novo
- [x] **T8** (A): chave inexistente não cria nota e vai para a DLQ
- [x] **E3** (A): `messageId` diferente para nota finalizada não altera nada
- [x] **E4** (I): mensagem malformada vai direto para a DLQ
- [x] **E5** (A): erro técnico faz 3 retries e depois vai para a DLQ
- [x] Logs em português, com `correlationId`

## Fatia 6 — Simulador do provedor

- [ ] Projeto `provider-simulator/` com `pom.xml` e `Dockerfile` próprios
- [ ] Consome a solicitação, espera o atraso configurado e publica resultado aprovável
- [ ] `messageId` do resultado derivado do `messageId` da solicitação
- [ ] Incluído no Docker Compose

## Fatia 7 — Integração com o broker real

- [ ] **T1** em nível I: o `POST` grava a nota e a solicitação chega à fila
- [ ] **T3** em nível I: o resultado publicado aprova a nota
- [ ] **T7** em nível I: reentrega **provocada pelo broker** (consumidor de teste fecha o canal sem `ack` e a mensagem volta com `redelivered = true`)
- [ ] **T7** em nível I com o **consumer real**: interceptador de teste fora do retry lança requeue depois do commit, o broker reentrega, e a nota é atualizada uma única vez (mesmo `approvedAt`), com uma linha em `processed_messages` e a DLQ vazia
- [ ] **T8** em nível I: chave inexistente vai para a DLQ
- [ ] **T9** em nível I: falha de publicação seguida de recuperação
- [ ] Explicação de como a reentrega foi provocada anotada para o README

## Fatia 8 — Validação e revisão

Só verificação: nenhuma funcionalidade nova. Os prompts dos agentes serão definidos junto com o autor quando a fatia começar.

- [ ] Agente de revisão de segurança (por exemplo, segredos, entradas, SQL, logs com dados sensíveis, imagens e Compose)
- [ ] Agente de revisão de boas práticas de código (arquitetura hexagonal, convenções do `CLAUDE.md`, código sem uso)
- [ ] Revisão de aderência à spec (contratos, regras e cenários T1–T10 e E1–E10 cobertos)
- [ ] Achados triados: corrigidos ou registrados como limitação
- [ ] Build e todos os testes passando depois das correções

## Fatia 9 — README

- [ ] Como subir o ambiente
- [ ] Como executar os testes
- [ ] Como cadastrar e consultar uma nota
- [ ] Como simular o provedor
- [ ] Fluxo completo da nota
- [ ] Contratos das duas mensagens
- [ ] Estratégia de recuperação de falhas de publicação
- [ ] Como a solução trata mensagens duplicadas e reentregas
- [ ] Como a reentrega foi provocada no teste de integração
- [ ] Decisões técnicas, limitações e itens deixados de fora
- [ ] Registro de uso de IA (o que foi gerado e o que foi revisado ou corrigido)

## Entrega

- [ ] `docker compose up` sobe tudo do zero, sem contas externas
- [ ] Build e todos os testes passam
- [ ] Nenhum segredo versionado
- [ ] Revisão final do código (por exemplo, `/code-review`)
- [ ] Repositório publicado no GitHub
