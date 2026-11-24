# Preparação da desativação — entrega 1

O frontend permanece somente leitura para o agente do backend. Seus controles
demonstrativos não devem ser conectados a esta camada interna. Não há endpoint,
página de confirmação, envio SMTP ou coleção Postman nova nesta etapa. O texto
demonstrativo de reativação automática pelo login deverá ser corrigido pelo
responsável pelo frontend quando o fluxo completo for integrado.

## Persistência

`010_create_account_deactivation_requests.sql` cria a tabela após a migration
009. `db/tables.sql` já inclui a estrutura para bancos novos; não aplicar 010
novamente nesses bancos. Não existe executor automático de migrations.
A aplicação no banco da aplicação depende de autorização explícita; o registro
da execução local autorizada está abaixo.

A tabela mantém uma solicitação atual por usuário, com FK `ON DELETE CASCADE`,
UUID e hash únicos e constraints de formato do hash e consistência temporal.
Substituição invalida o token anterior; consumo é de uso único; rollback restaura
a autorização. A tabela não guarda senha, endereço de e-mail ou token bruto.
Não foi criada uma outbox de e-mail nesta primeira entrega.

## Validação

`AccountDeactivationPersistencePostgresTest` usa Spring, transações JDBC e
PostgreSQL reais, sem JPA, mocks, chamadas HTTP ou transporte de e-mail. Não lê
`application.properties` e não executa DDL. O endereço aponta exclusivamente
para `127.0.0.1`, banco `sonarfy_deactivation_test`, porta padrão 55453, usuário
`deactivation_test` e senha exclusiva de teste `isolated-deactivation-test`.

Preparar somente um PostgreSQL descartável com essas configurações e importar
`db/tables.sql` em banco vazio. Executar:

```powershell
.\mvnw.cmd test '-Dtest=AccountDeactivationPersistencePostgresTest' '-Dsonarfy.test.deactivation-postgres=true'
```

Porta alternativa: `-Dsonarfy.test.deactivation-port=<porta>`. A CI habilita
explicitamente a classe e a executa tanto sobre o schema consolidado quanto após
simular o schema anterior e aplicar 010. A remoção dessa tabela para simular a
migration acontece somente no banco descartável da CI, nunca na aplicação.

Os cenários cobrem hash e prazo, isolamento, uso único, tokens inválidos/de outro
formato, substituição, invalidação, expiração, datas futuras, rollback e consumo
concorrente. Também verificam substituição e expiração enquanto uma confirmação
aguarda o bloqueio da conta, e preservação de elegibilidade, relações e sessões.

Testes HTTP pela cadeia real de segurança e roteiro manual com contas de teste
serão acrescentados quando houver endpoints. A execução desta classe não prova
autenticação HTTP, confirmação de senha, envio de e-mail ou desativação efetiva.
Não alterar contas reais para validar esta preparação interna.

## Evidências locais em 21/11/2026

- Baseline após criar a branch: 739 reportados, 657 executados, 82 ignorados,
  sem falhas/erros.
- Oito cenários PostgreSQL executados no schema consolidado e novamente após
  simular o schema anterior e aplicar a migration 010: sem falhas/erros nos dois
  caminhos. O banco utilizado foi exclusivamente o contêiner descartável
  `sonarfy-deactivation-persistence-test`, exposto em `127.0.0.1:55453`.
- Suíte completa com `sonarfy.test.deactivation-postgres=true`: 747 reportados,
  665 executados e 82 ignorados, sem falhas/erros. Os oito cenários novos estão
  incluídos nos 665; os outros testes opcionais não foram habilitados.
- Windows com JDK 21, memória limitada para Maven e JVM de testes; a opção
  `jdk.attach.allowAttachSelf=true` ficou restrita ao ambiente dos comandos.
- CI configurada para execução explícita nos dois caminhos de schema. CI remota
  não executada nesta entrega; nenhum commit, push ou PR foi realizado.
- Na conclusão da implementação, a migration ainda não havia sido aplicada no
  banco da aplicação. A execução local posterior autorizada está abaixo. Sem
  validação HTTP, Postman manual, SMTP ou integração frontend nesta etapa sem endpoints.

## Aplicação local autorizada em 21/11/2026

A migration 010 foi aplicada no banco da aplicação `sonarfy`, em
`localhost:5433`, após backup completo e confirmação da migration 009. Colunas,
constraints e preservação das 14 tabelas públicas anteriores foram verificadas;
`account_deactivation_requests` permaneceu vazia. Detalhes de recuperação do
serviço e backup estão em [persistência](../persistence.md#aplicação-local-da-migration-010-em-21112026).

Não executar a classe de testes nem importar o schema consolidado nesse banco.
Esta entrega continua sem endpoint: não há uma requisição de desativação para
executar no Postman. Os oito cenários funcionais já foram executados no banco
descartável; nenhuma solicitação foi inserida na aplicação pelo agente.

## Recupera??o validada em 24/11/2026

O stash `eecd0f1647c8d8a6026f6391e7a9d3f4094e652a` foi recuperado
sobre `98aaed1`, preservando sua refer?ncia e todas as migrations 011?015,
tabelas e etapas de CI posteriores. N?o foi usado `stash pop`.

- Baseline ap?s criar a branch: `./mvnw.cmd test`, 841 reportados,
  719 executados, 122 ignorados, zero falhas/erros.
- Oito cen?rios de `AccountDeactivationPersistencePostgresTest` aprovados no
  schema consolidado atual e novamente ap?s remover somente a tabela de
  solicita??es e aplicar 010 no banco descart?vel.
- PostgreSQL 17.9 nativo, inst?ncia tempor?ria exclusiva em `127.0.0.1:55453`,
  banco `sonarfy_deactivation_test`. Docker n?o estava dispon?vel.
- Nenhuma consulta, migration ou altera??o no banco da aplica??o nesta retomada.
  O registro de aplica??o local em 21/11 acima ? hist?rico, n?o uma nova inspe??o.
- Sem push, PR ou execu??o de CI remota nesta retomada.
