# Persistência das classes existentes

`User` mantém uma associação com `Permission`, acessada por `getPermission()` e
`setPermission()`. Uma permissão pode ser compartilhada por vários usuários; não
há coleção inversa em `Permission`. Os campos técnicos de auditoria, senha e
reserva de e-mail foram preservados.

`User.displayName` corresponde a `users.display_name VARCHAR(60) NOT NULL`.
No cadastro, o serviço combina nome e sobrenome e limita o nome de exibição a
60 caracteres Unicode, sem dividir pares substitutos. Os nomes originais são
preservados. A edição e a exposição desse campo por DTOs ficam para outra entrega.

O construtor completo de `User` recebe `displayName` após `surname` e preserva
o valor informado. Quem usar esse construtor deve fornecer um nome de exibição
não nulo, com até 60 caracteres. O construtor vazio permanece disponível para o
JPA e para os fluxos que preenchem os campos por setters.

`AccessLog` mapeia explicitamente `access_logs`; seu atributo `dateTimeAccess`
corresponde a `logged_at`. O construtor recebe usuário e IP, e `@PrePersist`
preenche a data. O teste verifica a persistência do log pelo repositório; não
estabelece que o login registre acessos automaticamente.

## Inicialização e atualização do banco

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
preferência para cookies será implementado na entrega HTTP seguinte.

Cada dispositivo possui uma família identificada por `session_id`. A rotação
marca o hash anterior com `used_at` e cria um sucessor com validade de 30 dias
de inatividade. Somente hashes SHA-256 de segredos aleatórios são armazenados.
Reutilizar qualquer token consumido remove toda a família, preservando outros
dispositivos. Logout aceita tanto o token atual quanto um token consumido.

Emissão, rotação e revogação usam o mesmo bloqueio no usuário. Duas renovações
simultâneas do mesmo token têm um único sucesso, mas a segunda detecta reutilização
e encerra a família, inclusive o token emitido pela primeira. O cliente deverá
coordenar renovações concorrentes. A perda da resposta de renovação exige novo
login se o cliente tentar reutilizar o token anterior.

`rotate` rejeita uma transação externa e abre sua própria unidade transacional:
a revogação é confirmada antes da resposta 401, sem `noRollbackFor` genérico.
O resultado contém o ID do usuário, a preferência lembrar-me e o token bruto, ocultado no
`toString()`. Não retorna entidades JPA.

Hashes históricos devem permanecer enquanto a sessão puder ser renovada.
O expurgo automático de famílias inteiramente expiradas segue pendente; apagar
apenas registros consumidos perderia a detecção de reutilização. A política
de cookies fica para a integração HTTP; esta entrega não impõe prazo absoluto.

```powershell
psql -h <host> -p <porta> -U <usuario> -d <banco> -v ON_ERROR_STOP=1 -f db/migrations/003_create_refresh_tokens.sql
psql -h <host> -p <porta> -U <usuario> -d <banco> -v ON_ERROR_STOP=1 -f db/migrations/004_add_refresh_token_sessions.sql
psql -h <host> -p <porta> -U <usuario> -d <banco> -v ON_ERROR_STOP=1 -f db/migrations/005_add_refresh_token_remember_me.sql
```

O schema completo está versionado em `db/tables.sql` e deve ser
importado somente em um banco vazio. O Compose e os demais arquivos de
infraestrutura local continuam ignorados pelo Git.

Em bancos existentes que já contêm `users`, aplique as migrações
`001_create_spotify_connection.sql` e `002_add_spotify_authorization_version.sql`,
nesta ordem, antes de disponibilizar a versão que persiste a conexão Spotify.
A primeira cria a tabela da conexão; a segunda adiciona a versão de autorização
ao usuário. Execute cada script uma vez e registre ambos no controle de deploy;
não há executor automático de migrações configurado.

```powershell
psql -h <host> -p <porta> -U <usuario> -d <banco> -v ON_ERROR_STOP=1 -f db/migrations/001_create_spotify_connection.sql
psql -h <host> -p <porta> -U <usuario> -d <banco> -v ON_ERROR_STOP=1 -f db/migrations/002_add_spotify_authorization_version.sql
```

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
