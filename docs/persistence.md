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

O schema completo está versionado em `src/main/infra/tables.sql` e deve ser
importado somente em um banco vazio. O Compose e os demais arquivos de
infraestrutura local continuam ignorados pelo Git.

Em bancos existentes que já contêm `users`, aplique
`db/migrations/001_create_spotify_connection.sql` antes de disponibilizar a
versão que persiste a conexão Spotify. Esse script cria somente a nova tabela,
sem recriar o banco ou modificar os dados existentes. Execute-o uma vez e
registre sua aplicação no controle de deploy; não há executor automático de
migrações configurado.

```powershell
psql -h <host> -p <porta> -U <usuario> -d <banco> -v ON_ERROR_STOP=1 -f db/migrations/001_create_spotify_connection.sql
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

## Evidências de 06/11/2026

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

Os relatórios locais ficam em `target/surefire-reports` e não são versionados.
