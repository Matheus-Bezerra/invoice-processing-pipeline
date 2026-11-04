# Invoice Processing Pipeline

Projeto de estudo para processamento assíncrono de notas fiscais, com foco em mensageria, consistência de dados e idempotência.

## Objetivo

Implementar uma API que recebe a chave de acesso de uma nota fiscal e solicita seu enriquecimento por meio de mensagens.

Após receber o resultado de um provedor simulado, o serviço aprova ou rejeita a nota conforme as regras de negócio.

O enunciado completo está em [DESAFIO.md](./DESAFIO.md).

## Status

🚧 Em desenvolvimento — planejamento e configuração inicial.

## Fluxo previsto

1. O cliente envia uma chave de acesso com 44 dígitos.
2. A API registra a nota no estado `ANALISE`.
3. O serviço publica uma solicitação de enriquecimento.
4. Um provedor simulado publica o resultado.
5. O serviço processa o resultado e atualiza a nota para `APROVADA` ou `REJEITADA`.

## Tecnologias

Em definição.

## Como executar

As instruções de execução local serão adicionadas após a configuração inicial.

## Testes

As instruções para executar os testes serão adicionadas durante a implementação.

## Documentação técnica

Serão documentados ao longo do desenvolvimento:

- contratos das mensagens;
- endpoints da API;
- tratamento de mensagens duplicadas e reentregas;
- recuperação de falhas na publicação;
- decisões técnicas e limitações.