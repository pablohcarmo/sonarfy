# Seguir e deixar de seguir

`PUT /api/profile/{targetId}/following` cria a relação; `DELETE` remove a relação.
Ambos exigem `Authorization: Bearer <access token>`, não recebem corpo e retornam
`204 No Content`, inclusive nas repetições. Não retornam DTO, estado, contadores
ou dados cadastrais. O ID do ator vem exclusivamente do principal autenticado.
O alvo usa o ID público de `ProfileDto.identity.id`.

- `400`: ID malformado ou relacionamento consigo mesmo (também no DELETE).
- `401`: credencial ausente, inválida, expirada, de propósito diferente de access,
  ator inexistente, inativo ou não verificado.
- `404`: alvo inexistente, inativo ou não verificado, sem distinguir os casos.
- Outros erros de infraestrutura não representam confirmação da operação; o cliente
  pode repetir a intenção após restabelecer o serviço.

A elegibilidade precede a idempotência: DELETE com relação ausente também exige
ambos ativos e verificados. Nenhum erro de elegibilidade altera relações existentes.
A ausência de acesso recente não altera elegibilidade. A data original é preservada
em PUT repetido. DELETE afeta somente (ator, alvo), sem remover a direção inversa.

## Persistência e concorrência

Reutiliza `User_Following` de `db/tables.sql`, incluindo PK composta, FKs,
restrição de auto-follow e data padrão. O contrato de mutação não requer migração; o índice de leitura é descrito em [perfis](profiles.md).
A transação adquire `FOR UPDATE` nas duas linhas de Users, uma por vez, por ID
crescente, relê active/verified do banco e só então executa INSERT ON CONFLICT
DO NOTHING ou DELETE. A consulta escalar evita estado antigo no contexto JPA.
Operações sobre pares com contas em comum são serializadas; pares independentes
podem progredir em paralelo. A PK continua sendo a garantia de unicidade no banco.

Não existe operação de desativação nesta entrega. Qualquer futura operação que
altere elegibilidade deve bloquear a linha de Users antes de reler e alterar seu
estado, conservar relações e manter o bloqueio até commit. Operações com várias
contas devem adquirir todos os bloqueios na mesma ordem crescente de ID e antes
de bloquear relacionamentos. UPDATE direto também espera pelo bloqueio da linha;
não implementar desativação com remoção das relações ou decisões fora da transação.
Se a desativação confirmar primeiro, a mutação seguinte será rejeitada. Se a
mutação confirmar primeiro, a desativação preservará seu resultado.

A leitura de contagens e viewer.isFollowing está descrita em [perfis](profiles.md).
Contas inativas ou não verificadas não entram nas contagens. Reativação restaura
somente relações preservadas. Listas e remoção de seguidor permanecem fora do escopo.

## Validação reproduzível

Use somente PostgreSQL descartável dedicado na porta 55451, database
`sonarfy_following_test`, usuário `following_test`, senha de teste
`isolated-following-test`. Não use o banco da aplicação. Exemplo PowerShell:

```powershell
docker run --name sonarfy-following-disposable -e POSTGRES_USER=following_test -e POSTGRES_PASSWORD=isolated-following-test -e POSTGRES_DB=sonarfy_following_test -p 127.0.0.1:55451:5432 -d postgres:17
Get-Content db/tables.sql | docker exec -i sonarfy-following-disposable psql -U following_test -d sonarfy_following_test
.\mvnw.cmd '-Dtest=FollowingPostgresTest' '-Dsonarfy.test.following-postgres=true' test
.\mvnw.cmd '-Dsonarfy.test.following-postgres=true' test
```

O contexto usa schema validate, credenciais fixas exclusivas de teste e não lê
application.properties. A preparação limpa relações e contas desse banco.
O teste inclui HTTP MockMvc pela cadeia real de segurança, persistência PostgreSQL,
24 mutações simultâneas em ambas as direções e espera observável em pg_stat_activity
por transação de desativação controlada, para ator/alvo e PUT/DELETE.
Não utiliza provedores externos. A suíte padrão pula os testes PostgreSQL opt-in.

## Integração contínua

O workflow Java CI provisiona um serviço PostgreSQL 17 exclusivo para esta suíte,
inicializa db/tables.sql com ON_ERROR_STOP e executa FollowingPostgresTest com
sonarfy.test.following-postgres=true. Falhas interrompem o job; os relatórios
Surefire são disponibilizados no artefato following-postgres-test-reports.
