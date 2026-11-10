# Checklist de implementação

Acompanhamento do que a [spec](spec.md) pede. Marque `[x]` **só depois** de o item estar implementado e os testes correspondentes estarem passando. Os IDs (T1, E4, C1, H1 etc.) são os cenários da seção 7 da spec.

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
- [x] **E2** (D, P), achado da revisão de segurança: `cnpj` com mais de 14 caracteres ou `amount` acima do limite rejeita a nota, em vez de falhar o `UPDATE` e mandar para a DLQ
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

## Fatia 6A — Chamada ao provedor pelo `worker`

- [x] Fila `invoice.enrichment.request` com DLX e DLQ `invoice.enrichment.request.dlq`
- [x] Port `ProviderClient` e client HTTP em `adapter/out/http`, com timeouts de conexão e de resposta configuráveis
- [x] Consumer da solicitação no `worker`, com `prefetch = 1`
- [x] Retry (3 retries, 2s, 4s e 8s) para `5xx`, timeout e conexão recusada; `4xx` e malformada direto para a DLQ
- [x] **C1** (A): solicitação consumida chama o provedor com o corpo e o `X-Correlation-Id` corretos, recebe `202` e dá `ack`
- [x] **C2** (A): `4xx` do provedor vai direto para a DLQ da solicitação, sem retry
- [x] **C3** (A): `5xx` ou falta de resposta faz 3 retries e depois DLQ
- [x] **C4** (A): solicitação malformada vai direto para a DLQ, sem chamar o provedor
- [x] Logs em português, com `correlationId` e sem dados sensíveis

## Fatia 6B — `invoice-webhook` (perfil `webhook`)

- [x] Perfil `webhook` sobe só com RabbitMQ, sem datasource, JPA nem Flyway, com health check
- [x] `POST /webhooks/enrichment-results` com interface e Swagger em português, e Swagger UI funcionando
- [x] Verificação do HMAC-SHA256 sobre o corpo bruto, em tempo constante, com o segredo vindo do ambiente
- [x] Validação do payload (mesmas regras de malformada de 3.2) e erros em `problem+json`, em português
- [x] Port `EnrichmentResultPublisher` e publicação com *publisher confirm* correlacionado, `mandatory` e *returns*
- [x] **H1** (W): assinatura válida e resultado bem formado publica e responde `202`
- [x] **H2** (W): sem `X-Signature` ou mal formada responde `401` e não publica
- [x] **H3** (W): corpo alterado depois de assinado responde `401` e não publica
- [x] **H4** (W): payload malformado responde `400` e não publica (um teste por motivo)
- [x] **H5** (A, W): falha na publicação (`nack`, timeout ou devolução) responde `503`
- [x] **H7** (W): Swagger UI disponível e documentando o endpoint em português
- [x] **H8** (W): o perfil sobe sem datasource, sem JPA e sem Flyway
- [x] Logs em português, sem o segredo, sem a assinatura e sem CNPJ ou valor

## Fatia 6C — Simulador do provedor e Compose

- [x] Projeto `provider-simulator/` com `pom.xml` e `Dockerfile` próprios, só HTTP (sem broker)
- [x] `POST /enrichment-requests`: valida o corpo, deduplica pelo `messageId` e responde `202` sem dados
- [x] Espera o atraso configurado e monta resultado completo e aprovável
- [x] `messageId` do resultado derivado do `messageId` da solicitação
- [x] Assina o corpo (HMAC-SHA256) e chama o webhook, com até 3 reenvios em `5xx` e timeout
- [x] Incluído no Docker Compose, junto com o `invoice-webhook` (seis serviços, healthchecks e dependências)
- [x] `.env.example` com `WEBHOOK_HMAC_SECRET`, `PROVIDER_BASE_URL` e `WEBHOOK_URL` de exemplo (sem segredos reais)
- [x] `docker compose up` completa o fluxo: nota criada e depois `APPROVED`

## Fatia 7 — Integração com o broker real

- [x] **T1** em nível I: o `POST` grava a nota e a solicitação chega à fila
- [x] **C1** em nível I: o `worker` repassa a solicitação por HTTP a um provedor de teste e recebe `202`
- [x] **C3** em nível I: provedor de teste fora do ar leva a 3 retries e à DLQ da solicitação
- [x] **H1** em nível I: o callback assinado ao `invoice-webhook` publica o resultado na fila
- [x] **H6** em nível I: o mesmo callback enviado duas vezes atualiza a nota uma única vez
- [x] **T3** em nível I: o resultado, entregue pelo webhook assinado, aprova a nota
- [x] **T7** em nível I: reentrega **provocada pelo broker** (consumidor de teste fecha o canal sem `ack` e a mensagem volta com `redelivered = true`)
- [x] **T7** em nível I com o **consumer real**: interceptador de teste fora do retry lança requeue depois do commit, o broker reentrega, e a nota é atualizada uma única vez (mesmo `approvedAt`), com uma linha em `processed_messages` e a DLQ vazia
- [x] **T8** em nível I: chave inexistente vai para a DLQ
- [x] **T9** em nível I: falha de publicação seguida de recuperação
- [x] Explicação de como a reentrega foi provocada anotada para o README

## Fatia 8 — Validação e revisão

Só verificação: nenhuma funcionalidade nova. Os prompts dos agentes serão definidos junto com o autor quando a fatia começar.

- [x] Agente de revisão de segurança (por exemplo, segredos, entradas, SQL, logs com dados sensíveis, imagens e Compose, e o webhook: HMAC em tempo constante, segredo fora dos logs, replay e SSRF)
  - [x] Achado: corpo do webhook sem limite antes do HMAC (DoS por memória). Corrigido com leitura limitada e `413` (**H9**)
- [x] Agente de revisão de boas práticas de código (arquitetura hexagonal, convenções do `CLAUDE.md`, código sem uso)
- [x] Revisão de aderência à spec (contratos, regras e cenários T1–T10 e E1–E10 cobertos)
- [x] Achados triados: corrigidos ou registrados como limitação
- [x] Build e todos os testes passando depois das correções

## Fatia 9 — README

- [x] Como subir o ambiente
- [x] Como executar os testes
- [x] Como cadastrar e consultar uma nota
- [x] Como simular o provedor
- [x] Fluxo completo da nota
- [x] Contratos das duas mensagens
- [x] Contrato do webhook (HMAC, respostas) e da chamada HTTP ao provedor
- [x] Adaptação do enunciado: o provedor chama um webhook e quem publica o resultado na fila é o `invoice-webhook`
- [x] Estratégia de recuperação de falhas de publicação
- [x] Como a solução trata mensagens duplicadas e reentregas
- [x] Como a reentrega foi provocada no teste de integração
- [x] Decisões técnicas, limitações e itens deixados de fora
- [x] Registro de uso de IA (o que foi gerado e o que foi revisado ou corrigido)

## Entrega

- [ ] `docker compose up` sobe tudo do zero (seis serviços), sem contas externas
- [ ] Build e todos os testes passam
- [ ] Nenhum segredo versionado
- [ ] Revisão final do código (por exemplo, `/code-review`)
- [ ] Repositório publicado no GitHub
