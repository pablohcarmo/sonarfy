# Consulta e edição textual de perfil

GET `/api/profile/me` e GET `/api/profile/by-handle/{handle}` exigem Bearer access
de uma conta existente, ativa e verificada. A consulta por handle ignora caixa,
retorna o handle canônico e não aceita `@` como prefixo especial. O segmento
`by-handle` permite consultar inclusive um usuário cujo handle seja `me`.

## Resposta 200 — ProfileDto

```json
{
  "identity": {
    "id": 1,
    "handle": "maria",
    "displayName": "Maria Música",
    "avatarUrl": null,
    "coverUrl": null,
    "biography": null,
    "location": { "city": "Campinas", "country": "Brasil" },
    "joinedAt": "2026-11-01T14:30:00Z"
  },
  "viewer": { "isSelf": true, "canEdit": true, "isFollowing": false },
  "statistics": { "followers": 0, "following": 0 }
}
```

Identidade contém exclusivamente ID, handle, nome de exibição persistido (até 60
caracteres, sem truncar valores existentes), URLs de mídia, bio, cidade/país e
data de entrada ISO 8601 com offset. Mídia e bio podem ser nulas. A leitura não
valida novamente URLs legadas nem realiza upload ou chamadas a provedores.

`viewer` é calculado pelo ID autenticado: consultar o próprio handle equivale a
`/me`. `isSelf` e `canEdit` indicam propriedade. `isFollowing` indica somente a
direção visitante → perfil; é false no próprio perfil. Relação inversa não implica
seguir. Parâmetros como actorId não substituem a identidade autenticada.

`statistics.followers` e `statistics.following` são inteiros não negativos (long)
e contam relações persistidas com a outra conta ativa e verificada. Falta de acesso
recente não exclui contas. Não retornam listas nem estatísticas de álbuns.
Desativação preserva as relações, mas retira a conta das contagens; reativação
restaura somente as relações ainda existentes. O mesmo filtro de visibilidade
aplica-se às contas não verificadas. Não existe endpoint de desativação nesta entrega.

Nome/sobrenome cadastral, e-mail, nascimento, permissões, credenciais e estado
Spotify não são retornados. Cidade/país e data de entrada são visíveis aos usuários
autenticados nesta fase. Não há configuração de perfil privado nem acesso anônimo.

## Erros e cache

- 401: Bearer ausente, inválido, expirado, com outro propósito ou conta autenticada
  inexistente, inativa ou não verificada.
- 404: handle sem correspondência ou perfil consultado inativo/não verificado.
  Não distinguir esses casos pelo contrato de erro. Handles fora do formato
  cadastral também não encontram perfil; não há validação de cadastro neste GET.
- Respostas de sucesso incluem `Cache-Control: no-store`, pois dependem do visitante.

## Compatibilidade e consultas

A entrega de relacionamentos acrescenta `viewer.isFollowing` e `statistics` ao
ProfileDto existente, mantendo os campos de identidade e propriedade. Clientes
com validação estrita das chaves devem atualizar seu schema. A evolução anterior
do formato cadastral para ProfileDto está registrada a seguir.

Esta entrega altera intencionalmente GET `/api/profile/me`: antes retornava
`UserDto`; agora retorna `ProfileDto` composto. Dados cadastrais continuam em
GET `/api/users/me`. PUT `/api/profile/me` agora também retorna `ProfileDto` e edita
somente texto, conforme o contrato abaixo. Essa é uma mudança intencional da resposta
anterior `UserDto` e do comportamento de substituição de mídia. O índice desta
entrega está descrito abaixo; não há alteração dos dados persistidos.

## Editar texto — PUT /api/profile/me

Exige Bearer access e `Content-Type: application/json`. Exemplo:

```json
{ "displayName": "  DJ 123 🎵  ", "biography": "Música e descobertas 🎧" }
```

- `displayName` omitido preserva o valor atual. Quando enviado, não aceita `null`,
  vazio ou somente espaços. Remove espaços Unicode das extremidades antes de
  validar o limite de 60 pontos de código. Aceita letras, números, símbolos e emojis,
  sem regex de formato. Preserva espaços internos e não normaliza o texto.
- `biography` omitida preserva o valor atual. `null` ou `""` limpa a bio, persistindo
  NULL. Outros textos são preservados literalmente, inclusive espaços, até 150
  pontos de código Unicode.
- Pontos de código não equivalem a grafemas: um emoji composto pode consumir mais
  de um ponto. Não truncar silenciosamente. No JavaScript, usar `Array.from(text).length`.
- Corpo `{}` preserva os valores. Corpo ausente, JSON nulo ou malformado retorna 400.
- Nome e bio são strings JSON ou null; números, booleanos, objetos e arrays não
  são convertidos implicitamente para texto. Números dentro de strings são aceitos.
- Campos fora do contrato, incluindo IDs, dados cadastrais, `avatar` e `wallpaper`,
  são ignorados. Mídia permanece inalterada mesmo quando enviada como `null`.
- HTTP 200 retorna o mesmo `ProfileDto` do GET, com `viewer.isSelf` e `canEdit` true
  e `Cache-Control: no-store`. Entrada inválida retorna 400 sem alteração parcial;
  credenciais inválidas ou conta autenticada inelegível retornam 401 pela segurança.
- A transação recarrega o usuário sob bloqueio antes de modificar texto, preservando
  alterações concorrentes de e-mail e senha. Não aceitar outro usuário pelo corpo.

O upload de avatar possui um endpoint separado com Azure Blob Storage e referências
no banco relacional: [contrato de avatar](profile-avatar.md). Ele não altera a semântica
do PUT textual. Wallpaper usa [upload separado](profile-wallpaper.md), preservando
proporção. Migração de imagens legadas continua pendente.

A projeção seleciona identidade, estado do visitante e contagens em uma única
instrução SQL, além da autenticação existente. Subconsultas correlacionadas evitam
multiplicação de linhas, carregamento de coleções e consultas por seguidor. Todos
os componentes usam o mesmo snapshot da instrução em READ COMMITTED, sem bloquear
mutações de relacionamentos. Uma leitura concorrente pode representar o estado
anterior ou posterior a um commit; não garante atualização após a resposta.
PUT textual e uploads que retornam ProfileDto também incluem os novos campos.

A migração `008_add_following_lookup_index.sql` cria o índice
`(following_id, follower_id)` para seguidores; a PK `(follower_id, following_id)`
já atende seguindo e a consulta do par. Atualizar bancos existentes com essa
migração antes da implantação. `db/tables.sql` contém o índice para bancos novos.
A migração foi aplicada ao banco local da aplicação com autorização em 20/11/2026,
conforme o registro abaixo.

Coleção importável: [sonarfy-profiles.postman_collection.json](sonarfy-profiles.postman_collection.json).
Integração: [profile-page.md](../integrations/profile-page.md).

## Validação da consulta — 19/11/2026

- Suíte imediatamente após criar `feat/profile-query`: 654 testes reportados,
  zero falhas/erros e 65 ignorados.
- `ProfileIntegrationTest`: oito cenários aprovados sobre H2 descartável, com
  MockMvc e cadeia real de segurança, serviços e JPA. Cobrem propriedade, campos
  permitidos, nulos, nome persistido com 60 caracteres, data com offset, conta
  cadastral preservada, alvos indisponíveis e rejeição de credenciais.
- Suíte completa final (`.\mvnw.cmd test`): 662 testes reportados, zero falhas/erros
  e 65 ignorados. Inclui rejeição de token expirado nos testes de perfil.
- JSON e sintaxe dos scripts da coleção de cinco requisições verificados.
  Não houve execução automatizada da coleção contra uma aplicação via rede.
- Validação manual no Postman realizada pelo usuário contra a aplicação local:
  GET `/api/users/me` preservado; GET `/api/profile/me` e consulta de outro perfil
  por handle retornaram 200, com identidade sem dados cadastrais privados,
  `viewer` correspondente à propriedade e `Cache-Control: no-store`.
  Consulta autenticada de handle sem correspondência retornou 404; ambas as
  rotas de perfil sem Authorization retornaram 401. Evidência fornecida pelo
  usuário em respostas JSON, capturas e confirmação dos status; não representa
  execução automatizada das asserções da coleção nem validação PostgreSQL específica.
- O teste opt-in PostgreSQL de troca de e-mail foi adaptado ao novo DTO, mas não
  executado nesta entrega. Sem validação PostgreSQL, plano EXPLAIN, navegador,
  integração frontend ou CI remoto nesta execução. Nenhuma migration aplicada.

## Validação da edição textual — 19/11/2026

- Branch `feat/profile-text-edit`, baseada na main `fa147aa`. Suíte inicial:
  662 testes reportados, zero falhas/erros e 65 ignorados.
- Suíte completa final (`.\mvnw.cmd test`): 683 testes reportados, zero falhas/erros
  e 65 ignorados. `ProfileIntegrationTest` contém 29 cenários de consulta/edição,
  com segurança real, serviços e JPA sobre H2 descartável.
- Os mesmos 29 cenários passaram no PostgreSQL 17.9 descartável, porta 55449,
  banco separado `sonarfy_profile_test`, com `db/tables.sql` importado e Hibernate
  em `validate`. Incluem nome com 60 emojis, bio com 150, limites excedidos,
  trim Unicode, campos omitidos/nulos, tipos JSON inválidos, isolamento, preservação
  de mídia e dados cadastrais e rejeição de autenticação inelegível.
- Para reproduzir os testes de perfil no banco descartável previamente preparado:
  `.\mvnw.cmd test -Dtest=ProfileIntegrationTest -Dsonarfy.test.profile-postgres=true`.
  A conexão é restrita ao banco local de teste e às credenciais descartáveis
  definidas no teste; a porta pode ser alterada com `sonarfy.test.profile-port`.
- H2 mede VARCHAR em unidades UTF-16. Somente no banco H2 de teste, as colunas
  textuais são ampliadas para representar os limites em pontos de código do
  PostgreSQL. A execução PostgreSQL confirma os limites reais do schema.
- Dois cenários adaptados de `EmailChangeConfirmationPostgresTest` passaram em
  outro banco descartável, `sonarfy_email_change_validation`: seleção por ID após
  reutilização de e-mail e edição concorrente preservando a confirmação de e-mail.
- Coleção JSON atualizada para dez requisições, com pasta de edição que altera
  a conta de teste. JSON, corpos e sintaxe dos scripts verificados; sem execução
  automatizada das asserções pela rede nesta etapa.
- Validação manual no Postman realizada pelo usuário na aplicação local após
  reiniciar a versão atual: PUT válido, corpo vazio `{}` e limpeza explícita da bio
  confirmados pelo usuário; PUT sem Authorization retornou 401. A atomicidade foi
  demonstrada gravando uma bio de referência, enviando nome null com outra bio
  (400) e consultando o perfil em seguida, que preservou a bio de referência.
  Evidência composta por respostas JSON e confirmação do usuário, sem registrar
  dados pessoais ou credenciais. Respostas anteriores no formato cadastral legado
  não foram consideradas evidência da versão atual.
- Contêiner descartável removido após os testes. Banco da aplicação, frontend,
  configurações locais e Azure preservados; sem migration. CI remoto não executado.

## Relacionamentos e contagens — 20/11/2026

- Branch baseada na main `7bf8aac`. Suíte inicial padrão: 722 testes, zero falhas/erros,
  70 ignorados (incluindo os testes PostgreSQL opt-in).
- Suíte completa final: `.\mvnw.cmd -Dsonarfy.test.following-postgres=true -Dsonarfy.test.profile-postgres=true -Dsonarfy.test.profile-port=55451 test`
  Resultado: 727 testes, zero falhas/erros e 65 ignorados.
- ProfileIntegrationTest: 33 cenários passaram em H2 (`.\mvnw.cmd -Dtest=ProfileIntegrationTest test`)
  e no PostgreSQL 17.11 descartável,
  banco sonarfy_profile_test separado, com MockMvc, segurança real, serviços e JPA.
  Incluem relações nas duas direções, terceiros, ator forjado por query string,
  próprio perfil, contagens vazias, desativação/reativação, verificação,
  relações removidas e leitura durante transação de elegibilidade não confirmada.
- O workflow Java CI executa os mesmos testes no PostgreSQL descartável, aplica
  a migração 008 duas vezes sobre schema sem o índice e publica relatórios.
  A configuração local do workflow não comprova execução remota.
- Migração 008 executada duas vezes em outro banco descartável, sem erro e com
  índice preservado. Somente esse banco de teste foi alterado. CREATE INDEX
  convencional pode bloquear escritas durante a criação; planejar sua aplicação
  no ambiente de destino. Nenhuma implantação realizada; a aplicação local da migração está registrada abaixo.
- EXPLAIN (ANALYZE, BUFFERS) de SQL representativo da projeção, com 10.000 contas
  sintéticas ativas/verificadas e 10.000 relações em ciclo após ANALYZE: o PostgreSQL
  usou uk_users_handle_ci no alvo, o índice reverso nos seguidores e a PK nos
  seguindo. A execução observada foi 0,084 ms com cache quente; não representa
  benchmark de produção ou de perfis com muitos seguidores. Contagens exatas
  continuam proporcionais ao número de relações elegíveis, sem cache persistido.
- Coleção Postman atualizada: JSON e sintaxe dos 20 scripts validados localmente.
  Validação manual no Postman realizada pelo usuário: próprio perfil com isFollowing=false;
  perfil seguido com isFollowing=true; PUT repetido preservando followers; DELETE
  refletido em isFollowing=false e followers=0 no alvo e following=0 no próprio perfil.
  Respostas fornecidas pelo usuário; não representa execução automatizada da coleção.
  Frontend, provedores externos e CI remoto não executados nesta entrega.

Para reproduzir o PostgreSQL, use apenas o contêiner descartável da entrega de
relacionamentos na porta 55451; crie o usuário profile_test com senha de teste
isolated-profile-test e o banco sonarfy_profile_test pertencente a esse usuário,
importe db/tables.sql com ON_ERROR_STOP=1 e execute:

```powershell
.\mvnw.cmd '-Dtest=ProfileIntegrationTest' '-Dsonarfy.test.profile-postgres=true' '-Dsonarfy.test.profile-port=55451' test
```

A configuração fixa de teste não lê application.properties. A preparação apaga
contas e relações apenas desse banco descartável. Os mesmos cenários de leitura
rodam em H2 na suíte padrão, sem depender de habilitação manual do PostgreSQL.

## Aplicação local da migração 008 — 20/11/2026

Aplicada com autorização ao PostgreSQL configurado localmente: localhost:5433,
banco sonarfy, schema public. Pré-verificação confirmou a tabela e somente a PK
existente. Executado psql com ON_ERROR_STOP=1, --single-transaction e o arquivo
008_add_following_lookup_index.sql. Resultado CREATE INDEX; catálogo confirmou
(following_id, follower_id), indisvalid=true e indisready=true. Nenhum registro
de usuário ou relacionamento foi alterado. Não representa implantação em outro
ambiente nem validação manual dos endpoints atualizados.
