# Álbum e avaliações da comunidade

## Estado da implementação

Entrega 3: `GET /api/albums/{spotifyId}`, autenticado, com metadados e agregados
da comunidade. As entregas anteriores forneceram cliente Spotify e catálogo.
Referência de catálogo: issue [#52](https://github.com/pablohcarmo/sonarfy/issues/52).

## Detalhes do álbum

`GET /api/albums/{spotifyId}` exige Bearer Sonarfy de conta ativa e verificada.
Não exige conexão Spotify do usuário. IDs devem conter 22 caracteres alfanuméricos.
Retorna `200` com `Cache-Control: no-store`:

```json
{
  "spotifyId":"4aawyAB9vmqN3uQ7FjRGTy",
  "name":"Album One",
  "coverUrl":null,
  "artist":{"spotifyId":"artist1","name":"Artist One"},
  "releaseOn":"2024-05",
  "releasePrecision":"mes",
  "totalTracks":70,
  "averageRating":8.25,
  "reviewCount":2
}
```

`averageRating` usa 0–10, arredondada a duas casas decimais. Contagem e média
incluem avaliações sem texto e nota zero, inclusive a avaliação própria. Consideram
somente autores ativos e verificados, independentemente de seguimento. Sem
avaliações elegíveis: média nula e contagem zero. Conta solicitante e agregados
são relidos no mesmo snapshot após resolver o catálogo. Desativação posterior
ao início desse snapshot aparece na próxima consulta; avaliações não são apagadas.
Datas e precisão podem ser nulas. Não são retornados IDs internos ou dados privados.

O catálogo completo é servido localmente. Se faltar álbum ou contagem oficial,
busca no Spotify com Client Credentials da aplicação (`SPOTIFY_CLIENT_ID` e
`SPOTIFY_CLIENT_SECRET` existentes). `SPOTIFY_CATALOG_MARKET` aceita duas letras
maiúsculas e tem padrão `BR`; o mercado é configurado no backend, sem parâmetro
correspondente no frontend. Tokens ficam somente em memória por instância,
com renovação antecipada; não há refresh token, scopes de usuário ou persistência
de nova conexão. Um 401 do provedor invalida apenas o token da aplicação usado
nessa chamada; a próxima tentativa obtém outro. Não há retry automático do GET.

| Status | Comportamento |
| --- | --- |
| 400 | ID malformado |
| 401 | Bearer ausente/inválido ou conta inelegível |
| 404 | Álbum não encontrado no Spotify |
| 502 | Credencial da aplicação rejeitada ou acesso negado pelo provedor |
| 503 | Provedor indisponível, resposta inválida ou rate limit |
| 504 | Timeout do provedor |

Resposta inválida de token retorna 502. No 429 da API de álbum, a resposta 503
preserva `Retry-After` positivo quando fornecido. Erros controlados não retornam
corpo externo nem credenciais e usam `no-store`. Falhas não geram catálogo parcial.
Aplicar previamente migrations 017 (catálogo) e 018 (índice de avaliações por álbum)
em bancos existentes. A lista da comunidade permanece para a próxima entrega.

## Contrato interno entregue

`AlbumCatalogService.resolve(spotifyId, accessTokenSupplier, market)` retorna um
registro local completo sem obter credencial nem acessar o Spotify. Se o registro
não existir ou `total_tracks` for nulo (legado/Ecos), obtém o token pelo supplier,
consulta o cliente de álbum e grava artista principal e álbum atomicamente.
O endpoint de detalhes fornece a credencial da aplicação e aplica a autorização
Sonarfy. O serviço de catálogo continua interno, sem conceder acesso HTTP por si só.

A leitura e a gravação usam transações separadas da chamada externa. A gravação
reutiliza os IDs por `spotify_id`, inclusive em concorrência, e preserva vínculos
de avaliações, faixas e escutas. Ecos continua gravando sem apagar `total_tracks`.
Não há refresh periódico nem chamada ao Spotify em consultas posteriores de um
álbum com total conhecido. Falhas remotas não gravam artistas/álbuns parciais.

Metadados respeitam os limites do schema: nomes até 500 caracteres, IDs até 64
alfanuméricos para artistas, capa HTTPS de até 255 caracteres. Datas são
normalizadas pelas mesmas regras de Ecos; datas inexistentes viram nulo com
precisão nula. O primeiro artista do álbum é o principal, conforme o modelo atual.
Não é calculada duração total a partir de páginas incompletas de faixas.

Migration para bancos existentes: `017_add_album_total_tracks.sql`. O campo
`total_tracks` é inteiro não negativo e aceita nulo para registros antigos.
O schema consolidado também contém essa coluna. Não aplicar schema completo
sobre banco existente. A aplicação local posterior autorizada está registrada
no [guia de persistência](../persistence.md).

`SpotifyAlbumService.fetch(accessToken, spotifyId, market)` consulta
`GET https://api.spotify.com/v1/albums/{id}` usando Bearer Spotify, distinto do
Bearer Sonarfy. O chamador fornece a credencial; este serviço não a obtém,
renova ou persiste. IDs devem conter 22 caracteres alfanuméricos; market é
opcional e aceita duas letras maiúsculas. Entradas inválidas são rejeitadas
antes de acessar a rede, com `IllegalArgumentException`.

Retorna `SpotifyAlbumResponseDto`: `id`, `name`, `type`, `albumType`,
`totalTracks`, `releaseDate`, `releaseDatePrecision`, `images` e `artists`.
As datas e sua precisão permanecem no DTO Spotify como `year`, `month`,
`day`; o armazenamento normaliza esses valores para `ano`, `mes`, `dia`.
`totalTracks` vem de `total_tracks`, sem contar a página de faixas ou as escutas.
Imagens podem estar ausentes ou vazias; não é criada uma capa artificial.
Campos externos desconhecidos são ignorados, inclusive a página `tracks`.

Resposta nula, ID divergente, tipo diferente de `album`, nome vazio,
total ausente/negativo ou artistas ausentes/inválidos resultam em `UNAVAILABLE`.
Assim, metadados essenciais incompletos não são apresentados como sucesso.

| Falha do provedor | Categoria interna |
| --- | --- |
| 401 | `UNAUTHORIZED` |
| 403 | `FORBIDDEN` |
| 404 | `NOT_FOUND` |
| 429 | `RATE_LIMITED`, preservando `Retry-After` inteiro positivo |
| Outros erros HTTP, conexão ou JSON inválido | `UNAVAILABLE` |
| Timeout de transporte sem status HTTP de erro | `TIMEOUT` |

Mensagens são fixas e não preservam corpo externo, token ou causa original.
Reutiliza Feign com 5 segundos de conexão, 10 segundos de leitura e sem retry
automático. A categoria `NOT_FOUND` aplica-se à consulta de álbum; Ecos mantém
sua tradução anterior de falhas da API.

## Escopo definido para as próximas entregas

- Listar avaliações, inclusive sem texto, com identidade pública do autor e data.
- Ordenar por mais recentes, mais antigas, maior nota e menor nota, com paginação.
- Considerar apenas autores ativos e verificados na lista e nos agregados.
- Reutilizar o gerenciamento da avaliação própria já existente.

A tela representa notas como estrelas de 0–5, preservando o contrato 0–10.
Busca por nome, likes, comentários e notificações não integram esta entrega.
A rota da lista da comunidade e seu DTO serão formalizados na próxima entrega.

## Validação reproduzível

Para o catálogo, prepare PostgreSQL descartável com banco vazio
`sonarfy_catalog_validation` e importe `db/tables.sql`. A classe de teste recusa
outros nomes de banco e hosts externos; os testes removem somente fixtures
desse banco dedicado. Com a simulação abaixo em execução:

```powershell
$env:SONARFY_CATALOG_TEST_JDBC_URL = 'jdbc:postgresql://127.0.0.1:55467/sonarfy_catalog_validation'
$env:SONARFY_TEST_DB_USER = 'postgres'
$env:SONARFY_TEST_DB_PASSWORD = ''
.\mvnw.cmd test '-Dtest=AlbumCatalogPostgresTest' '-Dsonarfy.test.catalog-postgres=true'
```

O teste cobre enriquecimento de álbum legado com avaliação existente, credencial
não solicitada no cache hit, suspensão de transação durante HTTP, resolução
concorrente sem duplicatas, rollback após falha de banco e migration reaplicada.
O ambiente da CI usa banco descartável próprio para essa classe.

Inicie a simulação local versionada em um terminal:

```powershell
postman simulation run test-support/spotify-recently-played.sim.yaml --port 55442 --no-history --output ndjson
```

Em outro terminal, execute:

```powershell
.\mvnw.cmd test '-Dtest=SpotifyAlbumServiceTest,SpotifyAlbumSimulationTest,SpotifyRecentlyPlayedSimulationTest,SpotifyRecentlyPlayedServiceTest' '-Dsonarfy.test.spotify-api-sim=true'
```

O teste usa HTTP e Feign reais contra respostas sintéticas. A CI executa os dois
testes de simulação. Nenhum resultado local estabelece acesso ao Spotify real,
disponibilidade em mercados ou elegibilidade do aplicativo em modo de desenvolvimento.

Fontes: [Get Album](https://developer.spotify.com/documentation/web-api/reference/get-an-album)
e descoberta Orbit (search/integrate em 24/11/2026). O mercado é opcional no
transporte; o país da conta Spotify pode prevalecer. Sem país nem mercado,
o provedor pode considerar o conteúdo indisponível.

### Resultado local — 24/11/2026

- Suíte após criar a branch: 873 reportados, 721 executados e 152 ignorados,
  sem falhas/erros.
- Suíte focada com simulação: 13 executados, sem falhas/erros.
- Suíte completa final com `-Dsonarfy.test.spotify-api-sim=true`: 876 reportados,
  725 executados, 151 ignorados, sem falhas/erros (`BUILD SUCCESS`).
- Eventos da simulação confirmaram as chamadas ao caminho de álbum com mercado
  BR e sem mercado. O cliente não seguiu o link da página de faixas.
- Evidências locais: `target/spotify-album-focused.log`,
  `target/spotify-album-full.log` e `target/spotify-album-simulation.ndjson`.
- Sem validação Spotify real, banco ou frontend. CI remota ainda não executada
  para esta alteração; testes PostgreSQL opt-in não foram habilitados.

### Entrega 2 — validação local em 24/11/2026

Os registros acima pertencem à entrega 1. Nesta entrega, testes iniciais foram
dispensados pelo usuário. Após a implementação:

- Suíte focada: `AlbumCatalogPostgresTest`, `SpotifyAlbumServiceTest`,
  `SpotifyAlbumSimulationTest` e `EchoCatalogMapperTest`: 22 executados,
  zero falhas/erros.
- Suíte completa com `-Dsonarfy.test.catalog-postgres=true` e
  `-Dsonarfy.test.spotify-api-sim=true`: 880 reportados, 729 executados,
  151 ignorados, zero falhas/erros (`BUILD SUCCESS`).
- PostgreSQL 17 descartável em `127.0.0.1:55467/sonarfy_catalog_validation`,
  inicializado com o schema consolidado. O teste de migration removeu somente
  a coluna no banco dedicado e reaplicou o script 017 duas vezes, preservando
  a identidade do álbum legado e verificando sua constraint.
- Simulação local em `127.0.0.1:55468/spotify-api`, configurada com
  `-Dsonarfy.test.spotify-api-url=http://127.0.0.1:55468/spotify-api`.
  Os eventos HTTP confirmaram o caminho do catálogo. Sem chamadas ao Spotify real.
- Relatórios locais: `target/catalog-focused.log`, `target/catalog-full.log`
  e `target/catalog-simulation.ndjson`. Nenhuma alteração no banco pessoal ou
  no frontend. CI configurada para executar a classe PostgreSQL, ainda sem
  resultado remoto desta entrega.

### Entrega 3 — validação local em 24/11/2026

Sem testes iniciais na troca de branch, conforme solicitado pelo usuário.

- Suíte focada: 61 executados, zero falhas/erros. Inclui os novos testes de
  token da aplicação e servidor HTTP e as regressões de autenticação e catálogo.
- Suíte completa: 884 reportados, 733 executados, 151 ignorados, zero falhas/erros
  (`BUILD SUCCESS`). Flags: `-Dsonarfy.test.catalog-postgres=true`,
  `-Dsonarfy.test.albums-postman=true`, `-Dsonarfy.test.spotify-api-sim=true` e
  `-Dsonarfy.test.spotify-api-url=http://127.0.0.1:55468/spotify-api`.
- PostgreSQL 17 descartável em `127.0.0.1:55467/sonarfy_catalog_validation`,
  previamente preparado com o schema até 017 e migration 018. O teste HTTP
  removeu somente o índice 018 nesse banco, reaplicou a migration duas vezes e
  confirmou os agregados sobre as avaliações preservadas.
- O servidor HTTP usa porta aleatória, segurança Bearer real e credenciais
  sintéticas da aplicação para Feign real contra a simulação Postman. A conta
  solicitante não tem conexão Spotify pessoal. Sem acesso ao Spotify real.
- A [coleção versionada](sonarfy-albums.postman_collection.json) executou quatro
  requisições e sete asserções, zero falhas, tanto na suíte focada quanto na completa.
  A coleção temporária recebeu JWT de teste em arquivo, removido ao final, sem
  publicar resultados. Execução opt-in com `-Dsonarfy.test.albums-postman=true`.
- Casos adicionais HTTP: média nula e nota zero incluída no cálculo, elegibilidade de autores e solicitante,
  edição/exclusão refletidas, cache local sem nova credencial, validação do ID,
  401/403/404/429/503 do provedor, `Retry-After: 900`, rejeição/invalidade do token
  da aplicação e invalidação após 401. A expiração antecipada do cache e a
  invalidação condicional foram verificadas com relógio controlado.
- Logs locais: `target/album-details-focused.log`, `target/album-details-full.log`,
  `target/album-details-simulation.ndjson` e `target/albums-postman.log`.
  A CI foi configurada para incluir os novos testes HTTP e a coleção. Resultado
  remoto ainda não observado. Banco pessoal e frontend não foram alterados.

### Migração autorizada e testes posteriores — 24/11/2026

Após autorização, migrations 017 e 018 foram aplicadas ao banco local da aplicação,
com backup restaurado e preservação das linhas anteriores, conforme o
[registro de persistência](../persistence.md#aplicação-local-das-migrations-017-e-018-em-24112026).

- Suíte completa pós-migração em banco descartável: 884 reportados, 733 executados,
  151 ignorados e zero falhas/erros. Inclui servidor HTTP real, segurança Bearer,
  PostgreSQL e coleção Postman (quatro requisições, sete asserções).
- Validação com Spotify real: aplicação compilada da branch, iniciada temporariamente
  em `127.0.0.1:18088`, usando banco vazio `sonarfy_album_live_validation`, com schema
  consolidado. Uma conta sintética foi inserida nesse banco e autenticada pelo login
  real com CSRF; nenhuma conexão Spotify pessoal foi criada.
- A primeira consulta do álbum público de exemplo `4aawyAB9vmqN3uQ7FjRGTy`, mercado
  `BR`, obteve token por Client Credentials e retornou 200 com metadados persistidos,
  18 faixas, média nula e zero avaliações. A coleção Postman passou contra esse
  servidor: quatro requisições, sete asserções, zero falhas.
- O servidor foi reiniciado com ambas as URLs Spotify apontando para um endereço
  local indisponível. A consulta do mesmo álbum retornou 200 e o mesmo JSON, sem
  necessitar de credencial em memória ou acesso ao provedor.
- Credenciais foram obtidas da configuração local do IntelliJ, sem impressão de
  valores ou publicação no Postman. O arquivo temporário da coleção foi removido;
  servidores temporários foram encerrados e o banco de validação real foi removido.
- Após todos os testes, contagens e fingerprints das 19 tabelas da aplicação ainda
  conferiam. Nenhum dado pessoal foi usado como fixture. Sem validação visual do
  frontend ou evidência de disponibilidade de todos os álbuns/mercados do Spotify.
- Evidências locais: `target/album-after-migration-tests.log`,
  `target/album-after-migration-simulation.ndjson`, `target/album-live-result.json`
  e `target/album-live-postman.log`. O funcionamento contra o provedor real foi
  observado para o álbum e o mercado testados, separadamente dos cenários simulados.
