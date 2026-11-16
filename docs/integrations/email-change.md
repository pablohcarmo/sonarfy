# Troca de e-mail de conta verificada

Esta entrega será integrada em três PRs dependentes:

1. Persistência das solicitações e da fila de e-mails (integrado no PR #61).
2. Solicitação autenticada, autorização específica, substituição e envio (recorte atual).
3. Confirmação, revogação de refresh tokens, notificação ao endereço antigo e
   cobertura HTTP do fluxo completo.

O recorte atual implementa a [solicitação autenticada](../api/email-changes.md),
mas mantém endpoint e despachante desativados por padrão (`APP_EMAIL_CHANGE_ENABLED=false`).
Habilitar somente em testes isolados até concluir o PR 3: a confirmação e a página
de frontend do link ainda não existem. Cadastro pendente, senha e Spotify mantêm
seus propósitos de autorização separados.

## Persistência e atomicidade

`email_change_requests` mantém uma solicitação vigente por usuário. O serviço
gera um UUID novo para cada solicitação e o remetente emite um JWT de propósito
`email_change`, vinculado a esse identificador. O UUID armazenado não autoriza
operações sem a assinatura do JWT. Nenhum token é armazenado nas novas tabelas.

Os records `EmailChangeRequestDto` e `EmailChangeMailDto` ficam em arquivos
próprios no pacote `dto`, independentes dos repositories e do serviço. Eles representam
os dados internos da persistência; não são contratos HTTP neste recorte.

`EmailChangeRequestRepository` persiste, consulta, substitui e consome solicitações,
incluindo o bloqueio da linha do usuário. `EmailChangeMailRepository` persiste e
reserva eventos, registra sucesso e reagenda tentativas. Nenhum deles coordena
operações no outro repository.

`EmailChangeService.replaceRequest` coordena a substituição e a intenção de envio
de `CONFIRM_NEW_EMAIL`, no mesmo commit, usando os dois repositories.
Isso não altera `users.email`. O identificador anterior deixa de ser encontrado;
seus eventos antigos permanecem identificáveis na fila para descarte pelo futuro
remetente. A autorização consumida e a expiração também são consultáveis.

O consumo usa atualização condicional por identificador, expiração e ausência de
consumo anterior. O serviço de confirmação deverá consumi-la na mesma transação
que altera `users.email`, revoga refresh tokens e enfileira `NOTIFY_OLD_EMAIL`.
Todas essas ações devem ocorrer após bloquear a mesma linha do usuário. As operações
de solicitação e enfileiramento participam da transação Spring do serviço, inclusive
quando gerenciada por JPA com o mesmo DataSource.

O campo `recipient` da fila preserva o destinatário do evento; a notificação ao
endereço antigo não deve resolver seu destino pelo e-mail atual do usuário.
Eventos não possuem FK para a solicitação substituível, mas pertencem ao usuário
e são removidos com ele. A fila não contém senhas, tokens ou links autenticados.

## Retentativas preparadas

A reserva de envio usa PostgreSQL `FOR UPDATE SKIP LOCKED`, uma identidade aleatória
da reserva e um prazo. Ela é confirmada em transação independente antes do futuro
SMTP. Se o processo parar, outro remetente poderá retomar o evento após o prazo.
Uma reserva antiga não pode concluir nem reagendar um evento retomado por outra.
O contador de tentativas e a próxima data ficam persistidos.

`EmailChangeRequestService` exige Bearer e senha atual, reserva o cooldown antes
de adquirir o bloqueio de usuário e usa `JpaTransactionManager` para persistir a
solicitação e o evento pelos componentes JDBC existentes. Não altera o endereço
atual nem revoga sessões. Senha incorreta também consome o cooldown de 30 segundos.

`EmailChangeDispatcher` roda a cada cinco segundos e processa até dez eventos por
ciclo, com reserva de dois minutos por evento. Descarta confirmações já substituídas,
expiradas ou consumidas. Falhas são reagendadas após 30, 60, 120, 240 e depois 300
segundos, respeitando a expiração original. Eventos entregues são retidos por sete
dias. Eventos obsoletos são removidos sem registrar entrega. Notificações ao endereço
antigo serão tratadas no PR 3; não são enviadas neste recorte.

As configurações ficam no `application.properties` local, que permanece fora do Git.
Replicar os parâmetros abaixo nos ambientes de execução; não há arquivo separado:

```properties
app.email-change.enabled=${APP_EMAIL_CHANGE_ENABLED:false}
spring.mail.properties.mail.smtp.connectiontimeout=5000
spring.mail.properties.mail.smtp.timeout=10000
spring.mail.properties.mail.smtp.writetimeout=10000
```

Os limites do SMTP compartilhado são cinco segundos para conexão, dez para leitura
e dez para escrita. O envio ocorre fora da transação de negócio.
Os logs registram somente a classe da falha, sem token, link ou senha.

A entrega de e-mail será pelo menos uma vez: uma queda após aceitação pelo SMTP e
antes de registrar sucesso pode causar envio duplicado. O consumo da autorização
continuará único. Esse mecanismo não garante que o provedor entregue na caixa de
entrada do destinatário.

## Evolução futura dos templates

Criar templates para recuperação/troca de senha, confirmação de troca de e-mail
e notificação ao endereço antigo, seguindo a identidade visual dos e-mails de
cadastro. Reutilizar `EmailTemplateService`, as versões HTML/texto, os recursos
inline e o record `EmailContent`. Essa padronização fica em entrega própria,
preservando os contratos e os propósitos das autorizações.

Nessa revisão, avaliar a movimentação de `EmailContent` de `services` para `dto`:
ele representa conteúdo renderizado, sem executar uma operação de serviço.

## Validação isolada

Preparar PostgreSQL descartável com banco `sonarfy_email_change_validation`,
separado do banco da aplicação, e importar `db/tables.sql`. Executar:

```powershell
.\mvnw.cmd '-Dtest=EmailChangePersistencePostgresTest,EmailChangeRequestPostgresTest' `
  '-Dsonarfy.test.email-change-postgres=true' `
  '-Dsonarfy.email-change.test.jdbc-url=jdbc:postgresql://localhost:55440/sonarfy_email_change_validation' test
```

Os testes exigem explicitamente esse nome de banco em localhost; removem a conta
de fixture a cada cenário. Não apontar para o banco da aplicação. A execução
normal ignora a classe opt-in. Os testes usam transações JDBC reais e não acionam
SMTP, provedores externos, Postman ou navegador.

A migration `006_create_email_change_requests.sql` é manual, após 005. Não foi
aplicada ao banco da aplicação. O schema consolidado já contém as novas tabelas;
não aplicar 006 novamente em banco criado com esse schema.

## Integração com a CI

O workflow `Java CI` executa os sete cenários opt-in em um banco separado,
`sonarfy_email_change_validation`. Nesse banco descartável, importa o schema
consolidado, remove somente as duas tabelas desta entrega para reproduzir o
estado anterior à 006 e aplica o script incremental com `ON_ERROR_STOP=1`.
As duas suítes validam persistência, solicitação HTTP e concorrência sobre as tabelas migradas.

A URL pode ser configurada por `SONARFY_EMAIL_CHANGE_TEST_JDBC_URL`, preservando
a precedência da propriedade Java já documentada. Usuário e senha usam
`SONARFY_TEST_DB_USER` e `SONARFY_TEST_DB_PASSWORD`. A restrição ao nome específico
de banco local continua obrigatória; nenhuma credencial da aplicação é necessária.

A confirmação futura deverá incluir um teste com `JpaTransactionManager`, cobrindo
rollback conjunto da mudança de e-mail em `User`, consumo via JDBC, revogação de
refresh tokens e evento de notificação. A cobertura atual usa
`DataSourceTransactionManager` e não comprova essa integração com JPA.

## Evidências deste recorte — 16/11/2026

### Solicitação e envio — PR 2

- Base atualizada após o merge #61 (`463a046`); suíte após cada troca de branch:
  538 reportados, zero falhas/erros e 37 ignorados.
- Suíte completa após centralizar propriedades no `application.properties`:
  547 reportados, zero falhas/erros e 46 ignorados. O teste específico do arquivo
  de propriedades separado foi removido junto dele.
- Execução conjunta após a centralização: 34 cenários aprovados no PostgreSQL
  descartável, sem ignorados, sendo sete da persistência e 27 da solicitação/envio.
- Antes dessa centralização, execução conjunta no PostgreSQL 17.9 descartável na
  porta 55442: 35 cenários aprovados, sem ignorados, sendo sete da persistência
  e 28 da solicitação/envio.
  O endpoint foi exercitado por MockMvc com a cadeia real de segurança e
  `JpaTransactionManager`; o transporte SMTP foi simulado.
- O YAML e a sintaxe Bash da etapa modificada de CI foram validados localmente.
  A nova versão do workflow ainda não foi executada remotamente.
- Coleção JSON e seus seis scripts de teste validados sintaticamente; não houve
  execução da coleção no Postman, envio SMTP real, frontend ou navegador.
- Nenhuma migration nova ou alteração no banco da aplicação. O fluxo permanece
  desativado por padrão, e a confirmação será entregue no próximo PR.

O diff deste recorte supera o alvo de aproximadamente 300 linhas pela cobertura
HTTP de autorização, cooldown concorrente, processamento da fila e desativação
segura do fluxo incompleto. Solicitação e envio permanecem juntos para revisão
do comportamento completo desse segundo recorte; separar seus testes deixaria
as novas garantias sem proteção de regressão.

### Histórico da persistência — PR 1

- Suíte imediatamente após criar a branch: 531 testes reportados, zero falhas/erros,
  30 ignorados.
- Suíte completa após a implementação: 537 reportados, zero falhas/erros,
  36 ignorados. Os seis cenários novos são opt-in e foram executados separadamente.
- PostgreSQL 17.9 descartável na porta 55440: seis testes aprovados, sem ignorados,
  cobrindo substituição, consumo único/concorrente, expiração, rollback da
  solicitação com seu evento, concorrência de reservas e retomada/reagendamento.
- Migration 006 aplicada somente em outro banco de validação do mesmo contêiner,
  criado com o schema anterior. O usuário de fixture permaneceu no banco, e os
  schemas resultantes dos caminhos consolidado/incremental foram comparados via
  `pg_dump --schema-only` e considerados iguais, removendo apenas as diretivas
  aleatórias de proteção do dump.
- Nenhum endpoint foi alterado; não houve execução Postman, envio SMTP real ou
  validação no navegador. O banco da aplicação não recebeu a migration.
- Após separar DTOs, repositories e serviço: suíte completa com 538 reportados,
  zero falhas/erros e 37 ignorados; sete cenários de
  `EmailChangePersistencePostgresTest` executados no PostgreSQL descartável, todos
  aprovados. O novo cenário confirma que persistir a solicitação diretamente no
  repository não enfileira e-mails; o serviço coordena essa decisão e sua transação.
- Ajuste da CI validado localmente: YAML e sintaxe Bash conferidos; a sequência
  consolidado → remoção das duas tabelas → migration 006 foi reproduzida em
  PostgreSQL 17.9 descartável. Os sete testes passaram usando URL e usuário por
  variáveis de ambiente. Isso não representa execução remota do workflow modificado.
