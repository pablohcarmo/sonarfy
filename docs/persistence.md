# Persistência das classes existentes

A preparação interna de desativação adiciona a tabela
`account_deactivation_requests` pela migration 010, após 009. O schema
consolidado já a inclui para bancos novos. HTTP e envio são desabilitados por
padrão; a aplicação da migration no banco da aplicação exige autorização
específica. Consulte [persistência e validação da desativação](integrations/account-deactivation.md).

O núcleo de solicitação adiciona `account_deactivation_mail_outbox` pela migration
016, após as migrations anteriores (incluindo 010 e 015). O schema consolidado
inclui a mesma tabela. Mantém uma intenção por usuário, destinatário verificado,
UUID da solicitação e reserva temporária; não persiste token bruto. A reserva
revalida a autorização vigente e rotaciona somente seu hash, sem ampliar o prazo.
Controller e remetente dependem de `app.account-deactivation.enabled=true`;
consulte o [contrato HTTP](api/account-deactivation-http.md). A aplicação local
autorizada da migration 016 está registrada abaixo.

## Aplicação local das migrations 015 e 016 em 24/11/2026

Após autorização explícita do usuário, o banco `localhost:5433/sonarfy`
(PostgreSQL 17.9) foi inspecionado. A migration 010 já estava aplicada com suas
colunas e constraints corretas e não foi reaplicada. Estavam pendentes:

1. `015_add_collection_rating_index.sql`: índice de ordenação da Coleção.
2. `016_create_account_deactivation_mail_outbox.sql`: outbox de desativação.

O Docker estava parado e o contêiner anterior não existia. O volume persistente
`infra_db_data` foi conferido primeiro em modo somente leitura: cluster PostgreSQL
17 existente, com `PG_VERSION` e controle íntegros, encerrado normalmente. O
contêiner `sonarfy_test_db` foi reconstruído com `postgres:17.9`, reutilizando esse
volume e publicando somente `127.0.0.1:5433`. A inicialização tinha guarda para
exigir o cluster existente; nenhum banco vazio ou volume novo foi inicializado.
O serviço permaneceu em execução após a operação.

Backup completo customizado, com catálogo conferido:
`target/db-backups/sonarfy-before-deactivation-015-016-20261124-054635.dump`.
O backup foi efetivamente restaurado em PostgreSQL 17.9 descartável e as contagens
e fingerprints das 18 tabelas foram comparadas com a aplicação, com ordenação
determinística `COLLATE "C"`. Os dados restaurados conferiram integralmente.
As migrations 015 e 016 foram ensaiadas nessa restauração antes da aplicação.

No banco da aplicação, ambas foram aplicadas em uma única transação, com limite
de espera por bloqueios e de execução. As tabelas anteriores foram protegidas
contra escrita durante a conferência; comparação antes do commit e novamente
após o commit confirmou que todas as linhas existentes foram preservadas.

Foram conferidos a definição do índice, as sete colunas e as quatro constraints
da outbox (PK, unicidade, FK e consistência da reserva). O catálogo passou a ter
19 tabelas; a outbox nova permaneceu vazia. Nenhuma conta, sessão, relação,
avaliação ou solicitação foi criada ou alterada para testes na aplicação.

A aplicação das migrations não habilita HTTP ou SMTP de desativação. A opção
continua desabilitada por padrão; a integração frontend e a homologação de envio
externo continuam pendentes. Os registros anteriores abaixo são históricos.

## Aplicação local da migration 010 em 21/11/2026

Após autorização explícita, `010_create_account_deactivation_requests.sql` foi
aplicada em uma única transação no banco local `sonarfy`, em `localhost:5433`.
O serviço estava indisponível após o reinício. O contêiner foi reconstruído pelo
serviço `db` do Compose, reutilizando o volume original `infra_db_data`, cuja
inicialização PostgreSQL 17 foi verificada previamente em modo somente leitura.
Nenhum banco vazio foi inicializado e o Adminer não foi iniciado.

A inspeção confirmou 009 presente e 010 ausente antes da aplicação. Backup
completo em formato custom, com catálogo verificado, em
`target/db-backups/sonarfy-before-deactivation-010-20261121-200716.dump`
(ignorado pelo Git). Não foi executado teste de restauração desse backup.

Após o commit, foram conferidas colunas, nulabilidade, chave primária,
unicidade, FK e constraints de hash e datas. As contagens e fingerprints das
14 tabelas públicas anteriores permaneceram iguais; a tabela nova ficou vazia.
Permaneceram quatro contas, duas relações, 29 registros de refresh e uma conexão
Spotify. Nenhuma conta ou solicitação foi criada ou modificada para testes nesse
banco. A validação funcional continua restrita ao PostgreSQL descartável.

A reativação acrescenta `users.access_token_version` e a outbox dedicada
`reactivation_mail_outbox`, por meio da migration 009 após 008. O schema
consolidado já contém ambos para bancos novos. Preparar a migration não autoriza
sua aplicação no banco da aplicação. Contrato e operação estão descritos em
[reativação](api/account-reactivation.md).

## Aplicação local da migration 009 em 21/11/2026

Após autorização explícita, `009_add_account_reactivation.sql` foi aplicada em
uma única transação no banco local `sonarfy`, acessado em `localhost:5433`
(PostgreSQL 17.9). A inspeção anterior confirmou a ausência da coluna e da outbox
novas e a presença do índice da migration 008.

Backup completo em formato custom, com catálogo verificado:
`target/db-backups/sonarfy-before-reactivation-009-20261121-180022.dump`
(ignorado pelo Git). Não foi executado um teste de restauração desse backup.

Após o commit, foram conferidos tipo, nulabilidade e default da coluna,
índice parcial, chave primária, FK e constraint de reserva da outbox. As quatro
contas receberam versão zero e a outbox permaneceu vazia. O fingerprint dos
dados anteriores das contas e as contagens de relações (2), refresh tokens (57)
e conexões Spotify (1) foram preservados. Nenhuma conta foi desativada ou
reativada pelo agente; nenhum teste com criação/remoção de tabelas foi executado
nesse banco. A validação manual posteriormente confirmada pelo usuário via
Postman está registrada em [integração de reativação](integrations/account-reactivation.md#validação-manual-confirmada-pelo-usuário-em-21112026).

`User` mantém uma associação com `Permission`, acessada por `getPermission()` e
`setPermission()`. Uma permissão pode ser compartilhada por vários usuários; não
há coleção inversa em `Permission`. Os campos técnicos de auditoria, senha e
reserva de e-mail foram preservados.

`User.displayName` corresponde a `users.display_name VARCHAR(60) NOT NULL`.
No cadastro, o serviço combina nome e sobrenome e limita o nome de exibição a
60 caracteres Unicode, sem dividir pares substitutos. Os nomes originais são
preservados. Essa composição define apenas o valor inicial no cadastro.
A edição de dados pessoais preserva o nome de exibição, cuja edição pertence
ao perfil. Ele permanece sem exposição em `UserDto` nesta entrega.

O PUT de dados pessoais usa transação e flush explícito. A unicidade sem distinção
de caixa depende do índice existente `uk_users_handle_ci` sobre `lower(handle)`;
não há migration nesta entrega. Somente a violação desse índice com SQLSTATE
`23505` é convertida em HTTP 409. Os seis dados pessoais permanecem `NOT NULL`.

O construtor completo de `User` recebe `displayName` após `surname` e preserva
o valor informado. Quem usar esse construtor deve fornecer um nome de exibição
não nulo, com até 60 caracteres. O construtor vazio permanece disponível para o
JPA e para os fluxos que preenchem os campos por setters.

`AccessLog` mapeia explicitamente `access_logs`; seu atributo `dateTimeAccess`
corresponde a `logged_at`. O construtor recebe usuário e IP, e `@PrePersist`
preenche a data. O teste verifica a persistência do log pelo repositório; não
estabelece que o login registre acessos automaticamente.

## Inicialização e atualização do banco

A preparação da troca de e-mail adiciona
`006_create_email_change_requests.sql`, após 005, com `email_change_requests` e
`email_change_mail_outbox`. Ela ainda não habilita endpoints ou envio de e-mails.
O schema consolidado já inclui as tabelas para bancos novos; não aplicar 006
novamente nesses bancos. A aplicação da migration no banco da aplicação depende
de autorização. Consulte [persistência e retentativas da troca de e-mail](integrations/email-change.md).

O controle de autorizações Spotify exige também
`db/migrations/002_add_spotify_authorization_version.sql` em bancos existentes.
Execute uma vez, após a migração 001 e antes de iniciar esta versão da aplicação.
A coluna `users.spotify_authorization_version` é `BIGINT NOT NULL DEFAULT 0`.
Não há aplicação automática das migrações. O schema completo já inclui a coluna
para bancos novos; não execute a migração 002 novamente nesses bancos.

A persistência de refresh tokens exige as migrations
`003_create_refresh_tokens.sql` e `004_add_refresh_token_sessions.sql`, nessa
ordem, após 001 e 002. A migration 004 também deve ser aplicada em bancos de
validação que já receberam 003: cada registro anterior recebe uma sessão própria.
O schema consolidado já inclui todas as colunas para bancos novos.

A migration `005_add_refresh_token_remember_me.sql` deve ser aplicada após 004
antes de iniciar esta versão em bancos existentes. Ela adiciona
`remember_me BOOLEAN NOT NULL DEFAULT FALSE`: registros anteriores recebem
`false`, sem alterar hashes, sessões, histórico ou expiração. Não execute 005
em um banco novo inicializado com o schema consolidado atualizado.

`issue(user, rememberMe)` persiste a preferência; `issue(user)` mantém o padrão
`false`. A rotação copia essa preferência para cada sucessor e a retorna no
`RotationDto`, sem consultar uma escolha reenviada pelo cliente. Os dois valores
mantêm 30 dias de validade por inatividade, sem prazo absoluto. O uso dessa
preferência determina se o cookie HTTP emitido no login e refresh é persistente
ou de sessão. O refresh permanece exclusivamente no cookie, não no JSON.

Cada dispositivo possui uma família identificada por `session_id`. A rotação
marca o hash anterior com `used_at` e cria um sucessor com validade de 30 dias
de inatividade. Somente hashes SHA-256 de segredos aleatórios são armazenados.
Reutilizar qualquer token consumido remove toda a família, preservando outros
dispositivos. O endpoint `POST /api/auth/logout` usa `revoke`, que aceita tanto o token atual
quanto um token consumido, removendo a família inteira. Não exige nova migration.

Emissão, rotação e revogação usam o mesmo bloqueio no usuário. Duas renovações
simultâneas do mesmo token têm um único sucesso, mas a segunda detecta reutilização
e encerra a família, inclusive o token emitido pela primeira. O cliente deverá
coordenar renovações concorrentes. A perda da resposta de renovação exige novo
login se o cliente tentar reutilizar o token anterior.

`rotate` rejeita uma transação externa e abre sua própria unidade transacional:
a revogação é confirmada antes da resposta 401, sem `noRollbackFor` genérico.
O resultado contém o ID do usuário, a preferência lembrar-me e o token bruto, ocultado no
`toString()`. Não retorna entidades JPA.

Troca e recuperação de senha usam o mesmo bloqueio pessimista na linha de usuário
e revogam todas as suas sessões na transação que altera hash e data da senha.
A revogação ocorre antes de modificar a entidade, pois `revokeAll` a recarrega
sob bloqueio. Uma rotação que confirmar antes terá seu sucessor removido; uma
que adquirir o bloqueio depois encontrará a sessão revogada. Rollback restaura
senha e sessões. Não há alteração de schema nem migration adicional.
`PasswordResetPostgresTest` repete os cenários de senha, revogação, rollback e
concorrência no PostgreSQL importado, usando validação de schema, sem DDL automático.

Hashes históricos devem permanecer enquanto a sessão puder ser renovada.
O expurgo automático de famílias inteiramente expiradas segue pendente; apagar
apenas registros consumidos perderia a detecção de reutilização. A política
de cookies está descrita em [autenticação](api/authentication.md); não há prazo absoluto.

```powershell
psql -h <host> -p <porta> -U <usuario> -d <banco> -v ON_ERROR_STOP=1 -f db/migrations/003_create_refresh_tokens.sql
psql -h <host> -p <porta> -U <usuario> -d <banco> -v ON_ERROR_STOP=1 -f db/migrations/004_add_refresh_token_sessions.sql
psql -h <host> -p <porta> -U <usuario> -d <banco> -v ON_ERROR_STOP=1 -f db/migrations/005_add_refresh_token_remember_me.sql
```

O schema completo está versionado em `db/tables.sql` e deve ser
importado somente em um banco vazio. O Compose e os demais arquivos de
infraestrutura local continuam ignorados pelo Git.

Em bancos existentes que já contêm `users`, aplique as migrações
`001_create_spotify_connection.sql`, `002_add_spotify_authorization_version.sql`,
`007_add_spotify_connection_status.sql` e `011_add_spotify_authorization_generation.sql`,
nesta ordem, antes de disponibilizar a versão que persiste a conexão Spotify com
controle de geração para Ecos (`011` depende apenas de `007`, sem depender de `010`).
Execute cada script uma vez e registre-os no controle de deploy;
não há executor automático de migrações configurado.

```powershell
psql -h <host> -p <porta> -U <usuario> -d <banco> -v ON_ERROR_STOP=1 -f db/migrations/001_create_spotify_connection.sql
psql -h <host> -p <porta> -U <usuario> -d <banco> -v ON_ERROR_STOP=1 -f db/migrations/002_add_spotify_authorization_version.sql
psql -h <host> -p <porta> -U <usuario> -d <banco> -v ON_ERROR_STOP=1 -f db/migrations/007_add_spotify_connection_status.sql
psql -h <host> -p <porta> -U <usuario> -d <banco> -v ON_ERROR_STOP=1 -f db/migrations/011_add_spotify_authorization_generation.sql
psql -h <host> -p <porta> -U <usuario> -d <banco> -v ON_ERROR_STOP=1 -f db/migrations/012_create_echoes.sql
```

A migração `012_create_echoes.sql` amplia `Artists.name` e `Albums.name` para `VARCHAR(500)`, cria `Tracks`, `Track_Artists` e `echo_sync_state`, adiciona `track_id` e o índice de paginação em `Listens`, e falha propositalmente caso `Listens` já contenha registros (garantia de integridade para bancos existentes ainda sem importação). Ela depende apenas do schema até `009`, sem depender de `010`.

A aplicação desta entrega também exige `011`, pois o writer utiliza a geração
da conexão. A migration `012` foi validada em PostgreSQL 17 descartável em
22/11/2026. A aplicação posterior no banco local está registrada ao final deste documento.

`EchoesPersistencePostgresTest` valida deduplicação, gravações concorrentes,
rejeição de conexão e geração antigas, conta inativa, escopo insuficiente e lote
anterior à reativação. O Hibernate apenas valida o schema preparado; a suíte
aceita exclusivamente o banco local dedicado `sonarfy_echoes_validation`.
A CI contém etapa que reconstrói o estado anterior à `012`, aplica a migration
e habilita explicitamente `-Dsonarfy.test.echoes-postgres=true`.
Na validação local de 22/11/2026, os cinco testes PostgreSQL passaram; a suíte
completa com essa propriedade reportou 768 testes, 685 executados, 83 ignorados
e nenhuma falha ou erro. Isso não constitui validação da CI remota ou do Spotify real.

Na revisão seguinte de 22/11/2026, a suíte PostgreSQL foi ampliada para oito
testes, acrescentando rollback após gravar catálogo e escutas, lotes parcialmente
sobrepostos em ordem inversa e lotes vazios ou totalmente inválidos. Os oito
passaram em uma instância PostgreSQL 17 temporária local, isolada em `target/`
e porta própria, sem Docker e sem acessar o banco da aplicação.
A suíte completa com os testes PostgreSQL de Ecos habilitados reportou 777
testes, 694 executados, 83 ignorados e nenhuma falha ou erro.

Forneça as credenciais pelo mecanismo seguro usado no ambiente. Não execute
o schema completo sobre um banco já inicializado. Se `spotify_connection` já
existir, confira sua estrutura antes do deploy; o script incremental falha
nesse caso, sem assumir que uma tabela existente está correta.

Recriar o volume não executa um arquivo SQL automaticamente sem uma configuração
de inicialização correspondente no contêiner. Importe o SQL antes de iniciar a
aplicação. Com `ddl-auto=none`, o Hibernate não cria nem atualiza as tabelas.
O schema precisa conter também a permissão `ROLE_USER` para o cadastro.

## Testes contra o schema existente

A suíte padrão (`.\mvnw.cmd test`) usa H2 isolado. Os testes convencionais de
integração criam e removem tabelas e não devem receber a conexão de um banco
que precise ser preservado.

Na CI, o job `test` também inicia um serviço PostgreSQL 17 descartável e importa
`db/tables.sql`. Para simular uma atualização incremental, remove
somente a tabela Spotify vazia e a coluna `users.spotify_authorization_version`
desse banco de teste; então aplica, em ordem, `001_create_spotify_connection.sql`
e `002_add_spotify_authorization_version.sql`. Também remove a tabela vazia
`refresh_tokens` e aplica `003_create_refresh_tokens.sql`, `004_add_refresh_token_sessions.sql`
e `005_add_refresh_token_remember_me.sql`. Em seguida, executa explicitamente
`ExistingSchemaIntegrationTest` e `RefreshTokenPostgresTest` com a propriedade opt-in habilitada. O segundo reutiliza os cenários de refresh e valida o schema sem criá-lo ou removê-lo. Falhas de SQL,
persistência, reconexão ou concorrência falham o mesmo job da suíte padrão. Isso
valida o rollout em teste; cada ambiente de deploy ainda exige aplicação manual
das migrações, em ordem, antes da nova versão da aplicação.

`ExistingSchemaIntegrationTest` é uma verificação separada, habilitada
explicitamente, com geração de schema desativada. Ela usa os serviços, JPA e
controllers via MockMvc, com e-mail simulado. Execute somente essa classe ao
apontar para o banco da aplicação:

```powershell
$env:SONARFY_TEST_JDBC_URL = 'jdbc:postgresql://localhost:5433/sonarfy'
$env:SONARFY_TEST_DB_USER = '<usuario-do-banco>'
$env:SONARFY_TEST_DB_PASSWORD = '<senha-do-banco>'
.\mvnw.cmd test '-Dtest=ExistingSchemaIntegrationTest' '-Dsonarfy.test.existing-schema=true'
```

O teste verifica cadastro com nome comum e nome completo acima de 60 caracteres,
login recusado para conta não verificada, emissão de token após marcar a conta
como verificada e persistência do log. A marcação de verificação é feita pelo
próprio teste; esse cenário não cobre a confirmação por e-mail nem HTTP via rede.
Também verifica a persistência de um nome de exibição explícito, diferente do nome
completo, informado pelo construtor de `User`, após limpar o contexto JPA.

Cada cenário usa transação com rollback. Usuários e logs de teste não permanecem
no banco, mas sequências de IDs podem avançar, conforme o comportamento do PostgreSQL.

## Evidências anteriores

### Revogação por senha em 16/11/2026

- `./mvnw.cmd test`: 491 testes reportados, zero falhas/erros, 29 ignorados.
- PostgreSQL 17.9 descartável, porta 55434, schema consolidado importado:
  `ExistingSchemaIntegrationTest`, `RefreshTokenPostgresTest` e
  `PasswordResetPostgresTest`: 39 testes reportados, zero falhas/erros e um
  ignorado. O caso legado de cutoff nullable é exclusivo do H2, pois o schema
  PostgreSQL exige `password_updated_at NOT NULL`; missing iat com cutoff
  preenchido também foi validado no PostgreSQL.
- Cobertura inclui troca e recuperação de senha, revogação de todos os
  dispositivos e históricos, preservação de outro usuário, concorrência entre
  senha e refresh, validação recusada e restauração de sessões por rollback.
- Nenhuma migration ou alteração no banco da aplicação nesta correção.

### Aplicação no banco local em 11/11/2026

Após autorização, foram aplicadas as migrations 004 e 005, nessa ordem, no banco
`sonarfy` do contêiner ativo `sonarfy_test_db` (PostgreSQL 17.9, porta local 5433).
A inspeção prévia confirmou a migration 003 e a ausência das colunas novas.
A tabela `refresh_tokens` estava vazia e permaneceu vazia; colunas, nulabilidade,
default `remember_me=false` e índice de sessão foram conferidos após o commit.
O backup anterior da tabela está em
`target/db-backups/sonarfy-before-refresh-004-005-20261111.dump` (não versionado).
Não foi executada suíte com criação/remoção de tabelas nesse banco.

### Preferência lembrar-me em 10/11/2026

- Suíte padrão: 453 testes reportados, zero falhas/erros, 18 ignorados; `BUILD SUCCESS`.
- PostgreSQL 17.9 descartável na porta local 55433: 15 cenários de
  `RefreshTokenPostgresTest` aprovados no schema consolidado e novamente após
  aplicar 005 sobre o schema anterior, com `ddl=validate`.
- A migration preservou integralmente 23 registros, incluindo histórico consumido
  e sucessor, e atribuiu `remember_me=false` a todos. Nenhuma migration foi aplicada
  no banco da aplicação. Cookies e contratos HTTP permanecem fora desta entrega.

### Evidências de 06/11/2026

- Após os ajustes do review: suíte padrão com 387 testes, zero falhas/erros e
  quatro itens opt-in ignorados. Em execução separada contra PostgreSQL 17
  temporário, os cinco cenários de `ExistingSchemaIntegrationTest` passaram
  após importar o schema e reaplicar a tabela Spotify pelo script incremental,
  incluindo reconexão com escopos ausentes e concorrência.

- Schema versionado e migração Spotify validados em PostgreSQL 17 temporário:
  importação completa em banco vazio e aplicação incremental em schema sem
  `spotify_connection`. Os dois caminhos produziram DDL idêntico para a tabela;
  o usuário de teste e as três permissões existentes foram preservados.
  Essa validação foi isolada do banco da aplicação.

- Suíte padrão após a correção do construtor: 360 testes aprovados e dois itens
  opcionais ignorados pelo Surefire, sem falhas
  ou erros; `BUILD SUCCESS`.
- PostgreSQL 17.9, banco `sonarfy` com o SQL importado: três cenários de
  `ExistingSchemaIntegrationTest` aprovados, incluindo persistência pelo construtor
  completo com nome de exibição explícito, sem falhas ou erros.
- `UserServiceTest` reexecutado após ampliar as asserções: 31 testes aprovados.
- Após os testes no PostgreSQL: zero usuários, zero logs e três permissões,
  confirmando o rollback dos dados criados.

## Evidência da branch Spotify em 08/11/2026

- `.\mvnw.cmd test`: 403 testes executados, zero falhas, zero erros e quatro
  ignorados. `BUILD SUCCESS`. A execução padrão usa H2; os quatro cenários
  opt-in de `ExistingSchemaIntegrationTest` não foram executados nessa chamada.
- Teste manual contra a aplicação local e o Spotify real: consulta inicial
  `DISCONNECTED`, autorização e callback concluídos, consulta `CONNECTED`,
  desconexão HTTP 204, consulta `DISCONNECTED` e nova autorização seguida de
  `CONNECTED`. Isso valida o fluxo funcional de conexão e reconexão; não valida
  renovação automática nem integração do frontend.

Os relatórios locais ficam em `target/surefire-reports` e não são versionados.

## Índice de avaliações por perfil

A consulta de Coleção adiciona `015_add_collection_rating_index.sql`, após 014:
`idx_reviews_user_rating_review_desc` em `(user_id, rating DESC, review_id DESC)`.
As duas direções de ordenação por nota usam o mesmo índice; o índice 014 continua
atendendo a ordenação por publicação. Não há alteração de tabelas ou dados.
Justificativa estrutural, sem afirmação de ganho quantitativo: o índice oferece
autor e fronteira composta na ordem do keyset. Não foi feito benchmark representativo.

Em 24/11/2026 UTC (23/11 no horário local), a importação do schema consolidado,
a aplicação incremental de 015 sobre schema sem esse índice e sua reaplicação
passaram no PostgreSQL 17.9 descartável em `127.0.0.1:55469`, banco
`sonarfy_collection_http_validation`. Contagem e fingerprint das avaliações
permaneceram iguais; o catálogo confirmou a definição e um único índice.
Nenhuma migração desta entrega foi aplicada no banco da aplicação.
O script usa IF NOT EXISTS; em tabelas grandes planejar janela, pois CREATE INDEX
pode bloquear gravações. Não importar o schema consolidado em banco existente.

A migration `014_add_profile_reviews_index.sql` cria
`idx_reviews_user_review_desc` sobre `Reviews (user_id, review_id DESC)`.
O índice atende a restrição por autor e a ordenação/fronteira da paginação
de avaliações próprias e por perfil, sem alterar dados ou constraints.
O índice de unicidade `(user_id, album_id)` continua garantindo uma avaliação
por álbum e usuário, mas não fornece a ordenação por `review_id`.

Aplicar a `014` após as migrações anteriores em bancos existentes antes de
disponibilizar a consulta por perfil. O schema consolidado inclui o índice para
bancos novos. `IF NOT EXISTS` permite reexecutar a migration. Em tabelas grandes,
planejar janela de manutenção, pois `CREATE INDEX` pode bloquear gravações.
Aplicada no banco local da aplicação conforme o registro abaixo.

### Aplicação local da migration 014 em 23/11/2026

Com autorização do usuário, a `014` foi aplicada em `localhost:5433/sonarfy`,
no contêiner `sonarfy_test_db`, usando o volume persistente existente
`infra_db_data` do Compose. Foi criado backup customizado em
`target/db-backups/sonarfy-before-profile-reviews-014-20261123-212253.dump`.
O inventário do arquivo foi conferido com `pg_restore --list`; não houve
ensaio de restauração.

A aplicação ocorreu em transação, com limites de espera e execução, conferência
da definição do índice e comparação de contagens e fingerprints de todas as
linhas das 18 tabelas antes/depois. Nenhum dado existente mudou; as cinco
avaliações anteriores permaneceram preservadas.

Os testes HTTP posteriores usaram contas, artista, álbuns e avaliações
temporários nesse banco. Após encerrar o backend de teste e remover somente
os registros criados para a validação, os fingerprints das 18 tabelas voltaram
aos valores anteriores. As sequências de identidade avançaram normalmente;
não foram retrocedidas. O banco do Compose permaneceu em execução.

## Reserva da sincronização de Ecos

A migration `013_add_echo_sync_lease_id.sql` adiciona `echo_sync_state.lease_id`
(UUID) após a `012`. É necessária antes de disponibilizar o endpoint de sync.
A aplicação no banco local está registrada abaixo. A posse do lease é
validada na mesma transação da gravação; liberação exige o UUID da execução.

### Aplicação local em 22/11/2026

Após autorização do usuário, o catálogo do banco `localhost:5433/sonarfy`
confirmou a estrutura das migrações anteriores, inclusive `011` e a estrutura
local de desativação reservada à `010`. Somente `012` e `013` estavam pendentes
e foram aplicadas juntas em uma transação. `Listens` estava vazia.

Foi criado backup completo em formato customizado em `target/db-backups/`
antes da alteração; o inventário do arquivo foi conferido, sem ensaio de restauração.
Contagens e fingerprints das linhas das 15 tabelas existentes foram comparados
antes/depois, usando suas colunas originais, e permaneceram iguais. Foram
conferidos as novas tabelas e FKs, o índice de paginação, os nomes com limite de
500 caracteres, `Listens.track_id` obrigatório e `echo_sync_state.lease_id` UUID.
Nenhum teste com criação/remoção de tabelas foi executado nesse banco.
