# Desafio — Pipeline assíncrono de notas fiscais

## Objetivo

Implementar um serviço que recebe a chave de acesso de uma nota fiscal, solicita seu enriquecimento por mensageria e processa o resultado para aprovar ou rejeitar a nota.

Este desafio é destinado a estudo. A linguagem, o framework e as bibliotecas ficam à sua escolha.

## Contexto

O cliente envia uma chave de acesso de nota fiscal com 44 dígitos. A nota nasce incompleta, no estado `ANALISE`.

Um provedor externo consulta essa chave e retorna:

- CNPJ do emitente;
- valor da nota;
- data de emissão;
- indicação de cancelamento.

Somente depois de receber esse resultado o serviço pode aprovar ou rejeitar a nota. A resposta do provedor pode ser entregue mais de uma vez.

## Tecnologias e infraestrutura

Utilize:

- linguagem e framework de sua preferência;
- PostgreSQL para persistência;
- um broker de mensagens executado localmente, como RabbitMQ, ou outra solução de mensageria de sua escolha;
- Docker Compose para subir os componentes necessários.

É permitido e incentivado utilizar filas reais. Não é necessário simular uma fila em uma tabela ou dentro do processo.

A solução deve funcionar localmente, sem depender de contas em nuvem ou serviços pagos.

## Fluxo esperado

1. O cliente envia a chave de acesso à API.
2. O serviço persiste a nota em `ANALISE` e providencia a publicação da solicitação de enriquecimento.
3. Um provedor externo recebe essa solicitação.
4. O provedor publica o resultado do enriquecimento.
5. O serviço consome o resultado e atualiza a nota para `APROVADA` ou `REJEITADA`.

O request do cliente não deve consultar o provedor por HTTP nem aguardar o enriquecimento.

O provedor deve ser representado nos testes por um simulador que publique a mensagem de retorno. Não integre um fornecedor real.

## Estados da nota

- `ANALISE`: aguardando o resultado do enriquecimento.
- `APROVADA`: enriquecimento completo e nota não cancelada.
- `REJEITADA`: nota cancelada ou enriquecimento incompleto.

Para este exercício, `APROVADA` e `REJEITADA` são estados finais. Novos resultados para uma nota já finalizada não devem alterar seus dados nem sua decisão.

## Contratos das mensagens

Defina e documente o formato das duas mensagens.

### Solicitação de enriquecimento

Deve conter, no mínimo:

- identificador da mensagem;
- identificador da nota;
- chave de acesso.

### Resultado do enriquecimento

Deve conter:

- identificador da mensagem;
- chave de acesso;
- CNPJ;
- valor;
- data de emissão;
- indicação de cancelamento.

CNPJ, valor e data podem estar ausentes ou nulos para representar um resultado incompleto.

O identificador da mensagem de resultado deve permanecer o mesmo em suas reentregas.

## Regras de negócio

1. A chave de acesso deve conter exatamente 44 dígitos. Não é necessário validar o dígito verificador fiscal.

2. A nota deve nascer em `ANALISE`, sem data de aprovação.

3. A nota só pode ser aprovada quando o resultado contiver CNPJ, valor e data de emissão, e indicar que ela não está cancelada.

4. Ao aprovar, o serviço deve persistir os dados recebidos e registrar a data de aprovação.

5. Um resultado cancelado ou incompleto deve rejeitar a nota e registrar um motivo compreensível. A data de aprovação deve permanecer vazia.

6. A mensagem de resultado não pode criar uma nota. Se a chave não existir, o consumo deve encerrar sem criar ou alterar registros de notas e sem provocar reentregas indefinidas.

7. A mesma chave não pode gerar duas notas, inclusive quando as requisições ocorrerem simultaneamente. Defina e documente a resposta da API para uma chave já cadastrada.

8. A reentrega da mesma mensagem de resultado não pode atualizar novamente a nota, alterar suas datas ou repetir efeitos de negócio.

9. Uma falha ao publicar a solicitação de enriquecimento não pode perder a nota nem deixar sua solicitação permanentemente esquecida. Deve existir uma forma recuperável de publicar a mensagem após a falha, sem exigir uma nova requisição do cliente.

10. A nota nunca pode ser aprovada durante o request de criação.

A estratégia para garantir consistência entre persistência e publicação fica à sua escolha. Documente seu funcionamento e suas limitações.

## API mínima

Disponibilize operações para:

- cadastrar uma nota pela chave de acesso;
- consultar uma nota por identificador ou chave.

A consulta deve permitir verificar o estado, os dados enriquecidos, a data de aprovação e o motivo de rejeição, quando aplicáveis.

Os caminhos dos endpoints e os códigos HTTP ficam à sua escolha, desde que documentados.

## Testes esperados

Demonstre com testes automatizados:

- criação da nota em `ANALISE`, sem data de aprovação, e publicação da solicitação;
- permanência em `ANALISE` enquanto nenhum resultado tiver sido processado;
- aprovação após o recebimento de um resultado completo e não cancelado;
- rejeição de uma nota cancelada;
- rejeição quando faltar CNPJ, valor ou data de emissão;
- duas requisições com a mesma chave, mantendo apenas uma nota;
- entrega da mesma mensagem de resultado duas vezes, sem repetir a atualização;
- recebimento de resultado para uma chave inexistente, sem criar nota;
- falha de publicação seguida de recuperação, preservando a nota e publicando a solicitação pendente;
- rejeição de uma chave com formato inválido.

Inclua ao menos um teste de integração com o broker real que demonstre publicação, consumo e reentrega. Explique como a reentrega foi provocada; chamar a função de processamento duas vezes não demonstra, sozinho, a reentrega pelo broker.

## Entrega

Disponibilize um repositório no GitHub contendo:

- código-fonte;
- Docker Compose para API, PostgreSQL, broker e eventuais workers;
- migrações ou mecanismo equivalente de criação do banco;
- testes automatizados;
- README na raiz.

O README deve explicar:

- como subir o ambiente;
- como executar os testes;
- como cadastrar e consultar uma nota;
- como simular o provedor;
- o fluxo completo da nota;
- os contratos das duas mensagens;
- a estratégia de recuperação de falhas de publicação;
- como a solução trata mensagens duplicadas e reentregas;
- decisões técnicas, limitações e itens deixados de fora.

Ferramentas de IA podem ser utilizadas. Caso use, registre brevemente o que foi gerado e o que você revisou ou corrigiu.

Não versione segredos, tokens ou credenciais reais. Forneça configurações de exemplo para execução local.

## Fora do escopo

- integração com fornecedor fiscal real;
- consulta à SEFAZ;
- validação fiscal completa da chave ou do CNPJ;
- infraestrutura em nuvem;
- autenticação e autorização;
- interface gráfica;
- ferramentas externas de observabilidade.

## Organização do estudo

Não há prazo obrigatório. Priorize primeiro o fluxo completo funcionando e, depois, os cenários de duplicidade e recuperação de falhas.

A avaliação deve considerar clareza, regras de negócio, confiabilidade do processamento assíncrono, testes e facilidade de execução local.