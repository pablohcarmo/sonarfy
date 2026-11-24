# Avaliações e resenhas de álbuns — gerenciamento próprio

Para a Coleção paginada com ordenações globais, filtro e totais, consulte o
[contrato de Coleção](profile-collection.md). As rotas próprias abaixo permanecem compatíveis.

Primeira entrega: criar, consultar, substituir, excluir e listar avaliações do usuário
autenticado, somente para álbuns já persistidos. Não depende do agrupamento de Ecos
do PR #84. A [consulta de detalhes do álbum](albums.md) resolve metadados ainda
ausentes no catálogo; as operações de avaliação continuam usando apenas o banco local.

Todas as rotas exigem `Authorization: Bearer <accessToken>` de conta ativa e
verificada. O autor vem exclusivamente da autenticação. Respostas de leitura e
criação/edição têm `Cache-Control: no-store`. Não retornam entidades JPA, e-mail,
localização ou dados cadastrais. A leitura de terceiros está disponível no
[contrato de avaliações no perfil](profile-reviews.md), também autenticado.
Consultas da comunidade por álbum estão disponíveis no
[contrato do álbum](albums.md#avaliações-da-comunidade), com ordenação e cursor próprios.

## Rotas

| Método e rota | Resultado |
| --- | --- |
| `POST /api/albums/{spotifyId}/reviews` | `201` com avaliação e `Location` apontando para a consulta própria por álbum |
| `GET /api/albums/{spotifyId}/reviews/me` | `200` com avaliação própria; `404` se ausente ou álbum desconhecido |
| `PUT /api/reviews/{reviewId}` | `200` com a avaliação substituída |
| `DELETE /api/reviews/{reviewId}` | `204`, sem corpo; exclusão física |
| `GET /api/reviews/me?limit=20&beforeId=123` | `200` com página da coleção própria |

`spotifyId` é `album.spotifyId` de Ecos; não é o ID interno de Albums. `reviewId`
é a identidade retornada pela avaliação e deve ser usada para editar/excluir.
O álbum e o autor são imutáveis. Campos extras não atribuem autor, álbum ou favorito.
Não há upsert: uma segunda criação retorna `409`, preservando nota, texto e datas.
Não existe `PATCH` nesta entrega.

## Nota, texto e substituição

O corpo de POST e PUT tem o mesmo formato:

```json
{"rating":8.5,"reviewText":"Um álbum para revisitar."}
```

- `rating`: número JSON obrigatório, de 0 a 10 inclusive, em múltiplos de 0,5.
  Strings, booleanos, null e campo omitido são rejeitados. A validação decimal
  ocorre antes de persistir em `NUMERIC(3,1)`, sem arredondar valores como 8,25.
  `8.50` é equivalente a `8.5`.
- `reviewText`: string opcional, até 255 pontos de código Unicode após remover
  espaços Unicode das extremidades. Espaços internos e quebras de linha são
  preservados. String vazia, só espaços, null ou campo omitido viram null.
  O caractere NUL é rejeitado por incompatibilidade com texto PostgreSQL.
- Não é permitido publicar texto sem nota.
- PUT substitui os dois campos editáveis. Omitir texto apaga o texto anterior;
  omitir nota é erro. Para preservar texto, envie-o novamente. `createdAt` permanece;
  `updatedAt` é atualizado. Favoritos não são alterados por PUT.
- Edições concorrentes são serializadas por conta; prevalece a última substituição
  executada sob o bloqueio. Não há controle otimista nem histórico de versões.

Exemplo de resposta de criação, consulta e edição:

```json
{
  "reviewId":42,
  "album":{
    "spotifyId":"local-album",
    "name":"Album",
    "coverUrl":null,
    "artist":{"spotifyId":"artist","name":"Artist"}
  },
  "rating":8.5,
  "reviewText":"Um álbum para revisitar.",
  "createdAt":"2026-11-23T16:00:00Z",
  "updatedAt":"2026-11-23T16:00:00Z"
}
```

Para remover apenas a resenha: `PUT /api/reviews/42` com
`{"rating":8.5,"reviewText":null}`; resposta `200`, `reviewText: null`.
Para excluir toda a avaliação: `DELETE /api/reviews/42`; resposta `204` vazia.
Uma nova exclusão do mesmo ID retorna `404`. Nova criação após excluir recebe novo ID.

## Paginação

`limit` padrão 20, mínimo 1, máximo 50. `beforeId`, se presente, deve ser inteiro
positivo de 64 bits. A ordenação é `reviewId DESC` (ordem de atribuição do ID da
publicação), independente de alterações de nota/texto e de empates em `createdAt`.
A primeira chamada omite `beforeId`; a próxima envia `nextBeforeId` sem modificá-lo.
`nextBeforeId: null` encerra a navegação. Cada consulta busca no máximo `limit + 1`
linhas com joins para álbum e artista, sem N+1 e sem contagem global.

```json
{"items":[],"nextBeforeId":null}
```

Página não vazia: `{"items":[<avaliações no formato acima>],"nextBeforeId":42}`.
O cursor é uma fronteira numérica, não um token de autorização: outro usuário
jamais obtém dados do autor original usando seu cursor. Dados inalterados não
sofrem repetições/omissões. Publicações novas exigem recarregar a primeira página;
exclusões podem encurtar páginas. Não há snapshot entre requisições.

## Erros

| Status | Exemplo e ação |
| --- | --- |
| `400` | `{"rating":8.25}`, texto longo/tipo incorreto, paginação ou ID malformado; corrigir entrada |
| `401` | Bearer ausente, inválido, expirado, propósito/versão incorretos, conta inexistente, inativa ou não verificada; renovar sessão conforme autenticação |
| `404` | Álbum desconhecido, avaliação ausente, excluída ou pertencente a terceiro; mesma resposta para evitar expor autoria |
| `409` | POST duplicado, inclusive concorrente; consultar `/reviews/me` do álbum e oferecer edição |

Exemplo de erro: `POST /api/albums/local-album/reviews` repetido retorna `409`.
Exemplo de ausência: GET de álbum sem avaliação própria retorna `404`, enquanto
a lista vazia retorna `200` com `items: []`. Não dependa do corpo de erro: segue
o mecanismo existente `ResponseStatusException`/Spring Security, sem novo envelope
estável. SQL, nomes de constraint e detalhes internos não fazem parte do contrato.

## Persistência, elegibilidade e compatibilidade

Reutiliza Reviews do schema consolidado: `uk_review_user_album`, FKs, checks de
intervalo e passo. A consulta por perfil adiciona o índice da migration `014`.
Banco existente precisa conter essa estrutura;
não importe `db/tables.sql` sobre banco já inicializado. `is_favorite` permanece
com default false na criação, sem endpoint de favoritos.

As mutações adquirem `FOR UPDATE` na linha de Users e releem active/verified antes
da gravação. A constraint é a garantia final de unicidade; `ON CONFLICT DO NOTHING
RETURNING` permite devolver `409` sem abortar a transação com erro SQL. A criação
não atualiza uma avaliação existente. A transação engloba a gravação e sua leitura.
Desativação confirmada antes do bloqueio impede a mutação; se confirmar depois,
preserva o resultado. Contas inativas não acessam as consultas; suas avaliações
permanecem armazenadas, disponíveis ao próprio autor após reativação e novo login.
Exclusão física da conta segue a FK `ON DELETE CASCADE` existente.

Amplia a lista de álbuns de Ecos com `rated` e `reviewId` da conta autenticada;
o contrato está descrito em [Ecos](echoes.md). A leitura por perfil está descrita
em [avaliações no perfil](profile-reviews.md), incluindo visibilidade de terceiros
e efeito de inatividade. Não implementa agregados, contadores, feed, eventos,
favoritos ou catálogo.

## Validação reproduzível

Prepare PostgreSQL 17 descartável, com banco vazio `sonarfy_reviews_validation`.
Importe `db/tables.sql` com `psql -v ON_ERROR_STOP=1 -f db/tables.sql`. A suíte
limpa dados desse banco e recusa URL fora de localhost/127.0.0.1 ou outro nome.
Nunca use o banco da aplicação.

```powershell
$env:SONARFY_REVIEWS_TEST_JDBC_URL = 'jdbc:postgresql://127.0.0.1:55461/sonarfy_reviews_validation'
$env:SONARFY_TEST_DB_USER = '<usuario-de-teste>'
$env:SONARFY_TEST_DB_PASSWORD = '<senha-de-teste>'
.\mvnw.cmd test '-Dtest=ReviewPostgresTest' '-Dsonarfy.test.reviews-postgres=true'
.\mvnw.cmd test
```

MockMvc exercita HTTP em processo pela cadeia real de segurança; JDBC grava no
PostgreSQL real, Hibernate usa validate. Não são chamadas de rede HTTP nem validação
visual. Nenhum provedor externo é chamado. A suíte padrão ignora os testes opt-in;
o workflow Java CI tem uma etapa explícita para executá-los em banco dedicado.
Sua inclusão no workflow não significa execução/aprovação da CI remota.

Coleção manual executável: [sonarfy-reviews.postman_collection.json](sonarfy-reviews.postman_collection.json).
Use conta de teste e álbum local ainda não avaliado: o ciclo cria, edita e exclui
avaliação. O teste de lista vazia exige coleção inicialmente vazia.

### Evidência local — 23/11/2026

- Baseline após criar a branch a partir de main: `.\mvnw.cmd test`, 806 testes
  reportados, 101 ignorados, zero falhas/erros.
- Versão final: `.\mvnw.cmd test '-Dsonarfy.test.reviews-postgres=true'`, 825 testes
  reportados, 724 executados, 101 ignorados, zero falhas/erros; BUILD SUCCESS.
- Inclui 12 casos de entrada e sete testes de integração com PostgreSQL 17.9
  temporário em `127.0.0.1:55461/sonarfy_reviews_validation`, schema consolidado
  importado em banco vazio. Concorrência HTTP: um `201`, sete `409`; desativação
  concorrente observada esperando por lock antes da rejeição `401`.
- Relatório local: `target/reviews-full-test.log`; relatórios individuais em
  `target/surefire-reports/`. Banco da aplicação não foi acessado.
- Na validação inicial, o JSON da coleção Postman foi validado, com 14 requisições
  e credenciais vazias. A execução por rede HTTP foi realizada na rodada abaixo.
  CI remota e integração visual não foram executadas nesta entrega.

### Rodada HTTP real — 23/11/2026

- `.\mvnw.cmd package '-DskipTests'`: BUILD SUCCESS. JAR real iniciado em
  `http://127.0.0.1:18086`, usando exclusivamente configuração isolada em `target/`.
  PostgreSQL 17.9 descartável em `127.0.0.1:55462/sonarfy_reviews_http_validation`,
  schema consolidado importado em banco vazio e Hibernate validate.
- Duas contas sintéticas ativas/verificadas, com login pelo endpoint real. A conexão
  Spotify foi preparada somente como fixture, com token sintético cifrado; não
  exercita OAuth real. A simulação existente foi iniciada com
  `postman simulation run test-support/spotify-recently-played.sim.yaml --port 55463 --no-history --output ndjson`.
- `POST /api/echoes/sync` importou uma escuta e persistiu `album1` a partir da
  resposta sintética. `GET /api/echoes` confirmou a identidade. A primeira tentativa
  do roteiro esperava duas escutas, mas a fixture retorna uma página incompleta
  de um item e o sync corretamente encerra a paginação; a expectativa foi corrigida
  e a rodada repetida após limpar apenas o banco descartável.
- `DELETE /api/spotify/connection` retornou 204; GET confirmou DISCONNECTED e a
  tabela de conexões ficou vazia. Todas as avaliações seguintes funcionaram sem
  conexão Spotify. O log da simulação permaneceu idêntico durante essa etapa.
- Cópia temporária da coleção versionada, com variáveis locais de teste:
  `postman collection run target/reviews-http-runtime.postman_collection.json --no-report-events --timeout-request 10000 --timeout-script 10000`.
  Resultado: 14 requisições, 21 assertions, zero falhas, exit code 0. A cópia com
  token foi removida após o uso; a coleção versionada continua sem credenciais.
- `python target/reviews-http-smoke.py`: 34 verificações HTTP complementares,
  exit code 0. Inclui criação após desconexão, duplicata 409, alteração/exclusão
  por terceiro 404, avaliações independentes para o mesmo álbum, ausência/invalidade
  de Bearer 401, conta inativa/não verificada 401, álbum desconhecido 404, nota
  inválida 400, substituição com remoção de texto, leitura do estado confirmado,
  duas páginas sem duplicatas e exclusão. O segundo álbum de paginação foi uma
  fixture local adicional; `album1` veio efetivamente do importador de Ecos.
- Ao final: zero avaliações e a escuta importada preservada no banco descartável.
  Relatórios locais: `target/reviews-http-postman.log`, `target/reviews-http-smoke.log`,
  `target/reviews-http-result.json` e `target/reviews-http-simulation.log`.
- Escopo da evidência: branch `feat/album-reviews` com alterações locais, antes de
  incorporar os três commits novos de origin/main. Não valida o Spotify real,
  consentimento OAuth, frontend ou CI remota. Banco da aplicação e frontend não
  foram alterados. Nenhuma falha do backend foi encontrada nessa rodada.

### Incorporação da main — 23/11/2026

- `git fetch origin` e `git merge --ff-only origin/main`: branch atualizada de
  `066a41c` para `ae21d4d`, incluindo PR #84 e a correção de limpeza das simulações
  na CI. Alterações locais preservadas por stash temporário e reaplicadas sem
  conflitos; arquivos novos e patch local conferidos após a incorporação.
- `.\mvnw.cmd test`: 827 testes reportados, zero falhas/erros, 109 ignorados.
- `.\mvnw.cmd test '-Dtest=ReviewInputTest,ReviewPostgresTest' '-Dsonarfy.test.reviews-postgres=true'`:
  19 casos executados, zero falhas/erros.
- `.\mvnw.cmd test '-Dtest=EchoQueryPostgresTest' '-Dsonarfy.test.echoes-postgres=true'`:
  seis testes executados, zero falhas/erros, incluindo consultas de álbuns de Ecos.
  Ambas as suítes PostgreSQL usaram bancos descartáveis dedicados na porta 55461.
- `.\mvnw.cmd package '-DskipTests'`: BUILD SUCCESS. O novo JAR iniciou na porta
  8080 usando as variáveis da configuração Sonarfy salva no IntelliJ, sem alterar
  seus valores. O processo foi iniciado fora do IDE e mantido em execução para o
  teste manual do usuário. Nenhuma chamada ao Spotify foi disparada nesta etapa.
- Logs locais: `target/reviews-after-main-test.log`,
  `target/reviews-after-main-postgres.log`, `target/reviews-after-main-echoes.log`
  e `target/sonarfy-after-main-server.log`. A rodada HTTP descrita acima permanece
  como evidência da revisão anterior; naquele momento o teste visual com a conta
  do usuário dependia de nova tentativa. A confirmação posterior está registrada
  abaixo. CI remota não foi executada.

### Estado Avaliado em Ecos — 23/11/2026

- `GET /api/echoes/albums` passou a retornar `rated` e `reviewId`, derivados da
  avaliação própria por join após o agrupamento. Sem migration ou chamadas externas.
- Teste PostgreSQL do ciclo POST/PUT/DELETE seguido de GET de Ecos confirma nota
  zero como avaliada, isolamento de autoria, estado após exclusão, contagens e
  estabilidade dos cursores. As suítes focadas ReviewPostgresTest e
  EchoQueryPostgresTest executaram 14 casos, zero falhas/erros.
- `.\mvnw.cmd test '-Dsonarfy.test.reviews-postgres=true' '-Dsonarfy.test.echoes-postgres=true'`:
  828 testes reportados, 740 executados, 88 ignorados, zero falhas/erros.
  Log: `target/reviews-echoes-rated-full-test.log`. PostgreSQL descartável local,
  com os bancos dedicados reviews/echoes de validação; nenhum teste destrutivo
  foi direcionado ao banco da aplicação.
- Coleção Postman de Ecos atualizada para verificar os campos e a relação
  `rated == (reviewId != null)`. JSON validado; essa nova assertion não foi
  executada pela CLI em HTTP real nesta rodada. A evidência funcional vem dos
  testes MockMvc com segurança e PostgreSQL reais. CI remota não executada.
- O frontend não foi alterado por este agente. Naquela etapa, a integração dos
  normalizadores e do renderer ainda estava pendente; a conferência posterior
  está registrada abaixo e nas [orientações](../integrations/reviews.md).

### Confirmação do usuário na aplicação — 23/11/2026

O usuário confirmou: “tudo certo com a funcionalidade de avaliações”. O registro
representa validação funcional relatada pelo usuário; não foram especificados
os casos individuais, temas, resoluções ou respostas HTTP dessa rodada.

Uma consulta posterior, exclusivamente de leitura, ao frontend confirmou que os
normalizadores preservam `rated`/`reviewId`, o botão alterna Avaliar/Avaliado e há
revalidação no retorno via `pageshow`. A implementação frontend foi realizada
em outra entrega. Nenhum teste ou alteração no frontend foi feito por este agente;
CI remota permanece sem evidência de execução nesta entrega.
