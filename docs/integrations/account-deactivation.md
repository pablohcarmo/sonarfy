# Preparação da desativação — operação e validação

O incremento 4 conecta HTTP e SMTP, desabilitados por padrão e ainda sem página
frontend integrada. Contrato vigente: [desativação HTTP](../api/account-deactivation-http.md).
Os registros dos incrementos 1–3 abaixo são históricos; a operação atual está na
seção do incremento 4 ao final deste documento.

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
A primeira entrega não criava outbox. O incremento 2b adiciona a tabela descrita
abaixo, ainda sem conectar o transporte de e-mail.

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

## Recuperação validada em 24/11/2026

O stash `eecd0f1647c8d8a6026f6391e7a9d3f4094e652a` foi recuperado
sobre `98aaed1`, preservando sua referência e todas as migrations 011–015,
tabelas e etapas de CI posteriores. Não foi usado `stash pop`.

- Baseline após criar a branch: `./mvnw.cmd test`, 841 reportados,
  719 executados, 122 ignorados, zero falhas/erros.
- Oito cenários de `AccountDeactivationPersistencePostgresTest` aprovados no
  schema consolidado atual e novamente após remover somente a tabela de
  solicitações e aplicar 010 no banco descartável.
- PostgreSQL 17.9 nativo, instância temporária exclusiva em `127.0.0.1:55453`,
  banco `sonarfy_deactivation_test`. Docker não estava disponível.
- Nenhuma consulta, migration ou alteração no banco da aplicação nesta retomada.
  O registro de aplicação local em 21/11 acima é histórico, não uma nova inspeção.
- Sem push, PR ou execução de CI remota nesta retomada.

## Incremento 2a — integração com credenciais

A preparação de solicitação HTTP foi dividida para fechar primeiro a invalidação
obrigatória antes de expor tokens. `AccountDeactivationInvalidationPostgresTest`
usa serviços de senha, e-mail e reativação reais, JWT assinado, JDBC/JPA e
`JpaTransactionManager` sobre o mesmo PostgreSQL descartável da persistência.
Renderização de e-mail e transporte são simulados; não há envio SMTP real.

Os cenários cobrem as cinco transições, isolamento entre contas, rollback de
credenciais/autorização/sessões, consumo aguardando bloqueio, credenciais
recusadas e reativação idempotente de conta já ativa. A CI executa ambas as
classes explicitamente nos dois caminhos de schema.

No incremento 2a, o contrato da solicitação HTTP foi definido em
[desativação](../api/account-deactivation.md),
mas continuava indisponível. Os próximos incrementos previstos eram outbox e reserva/rotação de
entrega sem token bruto, solicitação autenticada com senha e cooldown, confirmação
atômica com revogação de sessões e posterior integração frontend.

Antes de publicar a nova versão, verificar a presença real da migration 010 em
todos os bancos: alterações de senha/e-mail e reativação passam a depender da
tabela para invalidar autorizações. Não presumir a aplicação pelo registro
histórico local. Esta retomada não autoriza nem executa migration na aplicação.

### Evidência atual do incremento 2a

- `./mvnw.cmd test -Dtest=AccountDeactivationPersistencePostgresTest,AccountDeactivationInvalidationPostgresTest -Dsonarfy.test.deactivation-postgres=true`:
  24 cenários executados no schema consolidado, zero falhas/erros/ignorados.
- Após remover somente `account_deactivation_requests` e aplicar 010 no banco
  descartável: `./mvnw.cmd test -Dsonarfy.test.deactivation-postgres=true`,
  865 reportados, 743 executados, 122 ignorados, zero falhas/erros. Inclui os
  mesmos 24 cenários com a migration incremental.
- `git diff --check`: aprovado. CI configurada, sem execução remota.
- Nenhuma validação HTTP da desativação, SMTP real ou integração visual:
  não há endpoint de desativação. As evidências de 21/11 permanecem históricas.

## Incremento 2b — solicitação interna e outbox

O núcleo da solicitação, o controle de reenvio e a reserva/rotação agora estão
implementados. Continuam ausentes controller, transporte e agendamento de envio.
Não há configuração que habilite a desativação para usuários nesta versão.

A migration `016_create_account_deactivation_mail_outbox.sql` cria a outbox
depois das migrations existentes, incluindo 010 e 015. `db/tables.sql` contém a
mesma estrutura para bancos novos. Não aplicar 016 sobre esse schema já
inicializado; não existe executor automático de migrations. Aplicação no banco
da aplicação continua dependendo de autorização específica.

A outbox mantém uma intenção por usuário. Uma solicitação nova aceita substitui
a intenção anterior e sua reserva. Não há token bruto, senha ou corpo de e-mail
persistido. A tabela referencia o usuário; o vínculo com a autorização vigente
é revalidado por UUID em toda reserva. Após invalidação de credenciais, expiração
ou consumo, intenções obsoletas podem permanecer armazenadas, mas não podem ser
reservadas. Não há expurgo nesta entrega; o volume é limitado a uma linha por
usuário, removida por cascata se a conta for excluída por outro procedimento.

Chamadores internos de `enqueue` compartilham a transação da solicitação.
`claim` e `complete` confirmam suas próprias transações. Nunca envolver SMTP
em uma transação da conta. O futuro remetente deve conferir o vencimento antes
do envio, tratar falhas com `complete(delivery, false)` e tolerar resultado
incerto: recuperar uma reserva rotaciona o token e invalida o link anterior.
O atraso após falha é 30 segundos e a reserva abandonada expira em dois minutos;
nenhuma retentativa prorroga os 15 minutos da autorização original.

`AccountDeactivationRequestPostgresTest` exercita serviços reais com
`JpaTransactionManager` e PostgreSQL descartável. Cobre senha/limite BCrypt,
elegibilidade, cooldown após senha incorreta, rollback de substituição por falha
de outbox, preservação de conta e sessões, rotação com prazo original, reserva
concorrente, retomada de reserva abandonada e rejeição de eventos obsoletos.
Nenhum teste dessa classe chama HTTP ou SMTP.

Executar as três classes de desativação com:

```powershell
.\mvnw.cmd test '-Dtest=AccountDeactivationPersistencePostgresTest,AccountDeactivationInvalidationPostgresTest,AccountDeactivationRequestPostgresTest' '-Dsonarfy.test.deactivation-postgres=true'
```

A CI prepara o schema consolidado e repete as três classes após recriar somente
as tabelas de desativação pelas migrations 010 e 016, no banco descartável.
Próximo incremento: confirmação atômica e integração HTTP/SMTP, mantendo a
disponibilização externa condicionada à conclusão de todo o fluxo.

### Validação local do incremento 2b em 24/11/2026

- 37 cenários das três classes de desativação aprovados sobre o schema
  consolidado: 13 novos e 24 anteriores, sem falhas, erros ou ignorados.
- Após remover somente as tabelas de desativação no banco descartável e aplicar
  010 seguida de 016, `./mvnw.cmd test -Dsonarfy.test.deactivation-postgres=true`
  aprovou 878 reportados, 756 executados e 122 ignorados, sem falhas ou erros.
  Os mesmos 37 cenários estão incluídos nessa execução.
- Definição da tabela idêntica entre a migration 016 e o schema consolidado;
  `git diff --check` aprovado.
- PostgreSQL 17.9 nativo, instância temporária exclusiva em `127.0.0.1:55453`,
  banco `sonarfy_deactivation_test`. Nenhuma consulta ou alteração no banco da
  aplicação. Nenhuma validação HTTP, SMTP real ou frontend desta funcionalidade.
- CI atualizada para ambos os caminhos, ainda sem execução remota. Nenhum push
  ou PR nesta entrega. O stash original da preparação continua preservado.

## Incremento 3 — confirmação interna atômica

`AccountDeactivationConfirmationService` conclui a transição interna usando as
tabelas e colunas existentes. Não há nova migration. A autorização é consumida
sob bloqueio e a conta é recarregada; somente conta ativa e verificada prossegue.
O serviço revoga todos os históricos e sucessores de refresh, incrementa versões
de acesso e autorização Spotify e define `active=false`, com flush na mesma
transação do consumo. Falha em qualquer etapa reverte todos esses efeitos.

Relações, conexão Spotify, avaliações (incluindo favoritos/coleção), Ecos e
catálogo são preservados. As regras existentes de elegibilidade passam a
ocultar a conta inativa onde aplicáveis. Não há exclusão definitiva, desconexão
Spotify nem reativação implícita pelo login. A reativação explícita continua
exigindo credenciais atuais e login novo, sem restaurar sessões antigas.

O consumo impede reservas posteriores de envio. Autorizações antigas permanecem
recusadas mesmo depois da reativação; replay não revoga uma sessão criada após
ela. Solicitações recusadas por inelegibilidade não consomem a autorização nem
revogam sessões, pois a transação sofre rollback.

`AccountDeactivationConfirmationPostgresTest` usa o mesmo banco descartável
dedicado e serviços, JWT, JDBC/JPA e `JpaTransactionManager` reais. A rota de
prova protegida existe somente no código de teste, para exercitar a cadeia
Bearer antes e depois da transição/reativação. Não é um endpoint de desativação.
Renderização e transporte de e-mail permanecem simulados na configuração.

Os cenários verificam preservação por comparação das linhas de relações,
Spotify, avaliações, escutas e catálogo, isolamento entre contas, revogação de
históricos/sucessores, rejeição de login normal, reativação explícita, replay,
elegibilidade/prazo, confirmação concorrente e refresh aguardando a transição.
Uma falha injetada após flush real comprova rollback de estado, versões,
consumo e sessões, permitindo confirmar novamente a mesma autorização.

A CI inclui as quatro classes de desativação nos dois caminhos de schema.
Permanecem pendentes controllers, DTOs HTTP, integração SMTP, agendamento e
coleção Postman. O contrato planejado da confirmação está em
[desativação](../api/account-deactivation.md). O fluxo continua indisponível
externamente, sem propriedade que permita habilitá-lo parcialmente.

### Validação local do incremento 3 em 24/11/2026

- Base `7a128f5` (merge do PR #90). Após criar a branch,
  `./mvnw.cmd test`: 857 reportados, 719 executados, 138 ignorados, sem falhas/erros.
- Execução direcionada das quatro classes com
  `-Dsonarfy.test.deactivation-postgres=true`: 48 cenários aprovados no schema
  consolidado, incluindo 11 novos, sem falhas, erros ou ignorados.
- Após recriar somente as tabelas de desativação pelas migrations 010 e 016
  no banco descartável, `./mvnw.cmd test -Dsonarfy.test.deactivation-postgres=true`:
  889 reportados, 767 executados, 122 ignorados, sem falhas/erros. Inclui os
  mesmos 48 cenários no caminho incremental.
- `git diff --check`: aprovado. CI configurada, ainda sem execução remota.
- PostgreSQL 17.9 nativo, instância temporária exclusiva em `127.0.0.1:55453`,
  banco `sonarfy_deactivation_test`. Sem nova migration e sem consultar ou
  alterar o banco da aplicação. Frontend e stash original preservados.
- Sem validação HTTP da operação de desativação, Postman ou SMTP real: a cadeia
  Bearer foi exercitada somente pela rota de prova do teste. Sem push ou PR
  nesta entrega. As evidências dos incrementos anteriores permanecem históricas.

## Incremento 4 — HTTP e SMTP, desabilitados por padrão

O backend conecta solicitação autenticada, envio transacional por outbox e
confirmação pública por token. `app.account-deactivation.enabled=true` registra
controllers, remetente e configuração de agendamento. A ausência da propriedade
ou `false` mantém esses componentes ausentes; não foi alterada a configuração
local da aplicação para habilitar a funcionalidade.

O filtro de não armazenamento executa antes de CORS e autenticação para que
rejeições também recebam `Cache-Control: no-store`. A confirmação usa somente
o token no corpo e ignora Authorization; a solicitação exige Bearer real. Não
há confirmação por GET, cookie ou abertura do link.

### Preparação para disponibilizar

1. Manter o fluxo desligado enquanto a página e o adapter não estiverem prontos.
2. Verificar as migrations 010 e 016 em cada ambiente antes de habilitar.
   Esta entrega não adiciona migration e não as aplica na aplicação.
3. Configurar SMTP e `app.frontend-url` no mecanismo seguro do ambiente. O link
   usa `/confirm-account-deactivation.html#token=...` sob essa URL.
4. Integrar a página frontend e homologar CORS, fragmento, confirmação explícita,
   mensagens e sessão. Somente então habilitar a propriedade em todas as
   instâncias que atendem esse fluxo.

Para validação isolada, pode-se fornecer
`--app.account-deactivation.enabled=true` ao iniciar a API. O intervalo do
remetente usa `app.account-deactivation.dispatch-delay-ms` (padrão 5000 ms).
As configurações e credenciais locais ignoradas pelo Git não foram incluídas
no commit; não copiar credenciais para argumentos ou arquivos versionados.

Desabilitar a propriedade remove os endpoints e o remetente no próximo início
da instância, sem reativar contas já desativadas, limpar versões ou apagar dados.
Não recolhe um SMTP que já começou. Intenções persistidas continuam sujeitas ao
prazo original; ao retomar, o remetente revalida estado e reserva antes do envio.
Não usar rollback para uma versão que ignore a revogação por versão de acesso.

### Integração frontend pendente — somente leitura nesta entrega

O adapter atual mantém `deactivateAccount` indisponível. Ele deverá solicitar a
senha atual e tratar 202 como espera por e-mail, preservando a sessão e a conta
até a confirmação. Não enviar ID ou destinatário, nem implementar exclusão
definitiva por esse contrato. Respeitar 429; nova solicitação exige senha atual.

A página pública deverá capturar e remover o fragmento da URL, apresentar uma
ação explícita e enviar o token exclusivamente no JSON da confirmação, com
`credentials: 'omit'` e `cache: 'no-store'`. Não disparar o POST ao carregar a
página ou ao abrir o link. Em 400, orientar nova solicitação. Após 204, credenciais
locais da conta desativada devem ser descartadas; voltar a usar a conta exige
reativação explícita e novo login. A homologação visual fica com a entrega do
frontend; nenhum arquivo, branch ou dependência dele foi alterado.

### Coleção e transporte

[Coleção Postman](../api/sonarfy-account-deactivation.postman_collection.json):
executar `1 Request` sobre conta de teste ativa/verificada, capturar o token no
SMTP de teste e depois executar `2 Confirm`. A segunda pasta confirma, verifica
revogação e replay, reativa explicitamente e faz novo login. Ela altera a conta
de teste; nunca usar contas reais.

As execuções CLI separadas precisam compartilhar um ambiente local com
`accessToken`, `oldRefreshCookie` e `deactivationToken`. O login preenche as duas
primeiras durante a execução; salvar os valores localmente é responsabilidade
da ferramenta de validação, sem exportá-los para o repositório.

```powershell
postman collection run docs/api/sonarfy-account-deactivation.postman_collection.json -e CAMINHO_AMBIENTE_LOCAL -i '1 Request' --no-report-events
postman collection run docs/api/sonarfy-account-deactivation.postman_collection.json -e CAMINHO_AMBIENTE_LOCAL -i '2 Confirm' --no-report-events
```

`AccountDeactivationHttpPostgresTest` usa HTTP pela cadeia real do Spring MVC,
segurança, JWT e PostgreSQL; somente o transporte é simulado. Cobre fluxo completo,
autenticação, JSON/Content-Type, limite BCrypt, cooldown, CORS/no-store, replay,
retentativa sem ampliar vencimento e revalidação imediatamente antes de enviar.
`AccountDeactivationDisabledTest` cobre ausência/false da propriedade sem exigir
banco. A CI executa as cinco classes PostgreSQL nos dois caminhos de schema.

### Evidências atuais do incremento 4 — 24/11/2026

- Base `0552c34`, merge do PR #91. Após criar a branch, `./mvnw.cmd test`:
  863 reportados, 719 executados, 144 ignorados, sem falhas ou erros.
- Validação direcionada inicial com `./mvnw.cmd test '-Dtest=AccountDeactivation*Test' '-Dsonarfy.test.deactivation-postgres=true'`:
  69 cenários aprovados no schema consolidado. Depois foi acrescentado o teste
  de não registro de token em erro de binding, incluído na suíte final abaixo.
- Após recriar somente as tabelas de desativação pelas migrations existentes
  010 e 016 no PostgreSQL descartável, execução final de
  `./mvnw.cmd test -Dsonarfy.test.deactivation-postgres=true`: 911 reportados,
  789 executados, 122 ignorados, sem falhas/erros. Inclui 70 cenários de
  desativação: 48 anteriores, 20 HTTP/envio e dois de funcionalidade desligada.
- `./mvnw.cmd package -DskipTests`: API empacotada para validação integrada.
  A API foi iniciada com um arquivo temporário próprio via `spring.config.location`,
  apontando somente para PostgreSQL e SMTP em loopback, com credenciais sintéticas.
- Coleção Postman na API real: 14 requisições e 32 asserções aprovadas,
  `--no-report-events`, sem publicar resultados. Inclui solicitação, confirmação,
  replay, rejeição de access/refresh antigos, login recusado enquanto inativa,
  reativação explícita e novo login; o JWT antigo continua recusado após reativar.
- O transporte real `EmailService`/JavaMail foi exercitado contra receptor SMTP
  local, com rejeição 550 na primeira tentativa e aceite no reenvio. O segredo
  foi rotacionado e o token da tentativa anterior foi recusado com 400.
- Verificações adicionais na API real: CORS recusado com 403/no-store; após
  reiniciar com a opção desligada, ambas as rotas retornaram 404 com uma sessão
  válida na rota autenticada. Nenhum token bruto apareceu no log da API isolada.
- PostgreSQL 17.9 nativo em `127.0.0.1:55453/sonarfy_deactivation_test`; API em
  `127.0.0.1:55454`; SMTP em porta efêmera local. Não houve conexão com o banco
  da aplicação ou com provedor SMTP externo. Não há nova migration.
- JSON da coleção válido e `git diff --check` aprovado. CI atualizada, sem
  execução remota nesta entrega. Sem push, PR, alteração ou build do frontend.

Ainda pendentes para liberação: página e adapter frontend, homologação visual
e entrega/recebimento pelo provedor SMTP real, usando contas de teste autorizadas.
O backend permanece desabilitado por padrão até essas validações.
