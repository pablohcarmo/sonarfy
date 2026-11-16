# Troca de e-mail de conta verificada — preparação

Esta entrega será integrada em três PRs dependentes:

1. Persistência das solicitações e da fila de e-mails (este recorte).
2. Solicitação autenticada, autorização específica, substituição e envio.
3. Confirmação, revogação de refresh tokens, notificação ao endereço antigo e
   cobertura HTTP do fluxo completo.

Não há endpoint novo ou envio de e-mail ativo neste primeiro recorte. Os fluxos
de cadastro pendente, senha e Spotify permanecem com seus contratos existentes.
A coleção Postman de troca de e-mail será adicionada junto dos endpoints.

## Persistência e atomicidade

`email_change_requests` mantém uma solicitação vigente por usuário. O próximo
serviço gerará um UUID novo para cada solicitação e emitirá um JWT de propósito
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

O despachante, o intervalo de reserva, o backoff e a política de retenção serão
implementados no próximo PR. Ele deverá verificar se a confirmação ainda está
vigente antes do envio, descartar confirmações substituídas/expiradas/consumidas e
gerar o JWT respeitando a expiração original da solicitação.

A entrega de e-mail será pelo menos uma vez: uma queda após aceitação pelo SMTP e
antes de registrar sucesso pode causar envio duplicado. O consumo da autorização
continuará único. Esse mecanismo não garante que o provedor entregue na caixa de
entrada do destinatário.

## Validação isolada

Preparar PostgreSQL descartável com banco `sonarfy_email_change_validation`,
separado do banco da aplicação, e importar `db/tables.sql`. Executar:

```powershell
.\mvnw.cmd '-Dtest=EmailChangePersistencePostgresTest' `
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
A suíte então valida a persistência e a concorrência sobre as tabelas migradas.

A URL pode ser configurada por `SONARFY_EMAIL_CHANGE_TEST_JDBC_URL`, preservando
a precedência da propriedade Java já documentada. Usuário e senha usam
`SONARFY_TEST_DB_USER` e `SONARFY_TEST_DB_PASSWORD`. A restrição ao nome específico
de banco local continua obrigatória; nenhuma credencial da aplicação é necessária.

A confirmação futura deverá incluir um teste com `JpaTransactionManager`, cobrindo
rollback conjunto da mudança de e-mail em `User`, consumo via JDBC, revogação de
refresh tokens e evento de notificação. A cobertura atual usa
`DataSourceTransactionManager` e não comprova essa integração com JPA.

## Evidências deste recorte — 16/11/2026

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
