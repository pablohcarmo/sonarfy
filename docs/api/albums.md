# Álbum e avaliações da comunidade

## Estado da implementação

Entrega 2: resolução do catálogo local com persistência idempotente e busca no
Spotify quando faltar o álbum ou seu total oficial de faixas. Ainda não há novas
rotas HTTP Sonarfy nem alteração do fluxo de avaliações.
Referência de catálogo: issue [#52](https://github.com/pablohcarmo/sonarfy/issues/52).

## Contrato interno entregue

`AlbumCatalogService.resolve(spotifyId, accessTokenSupplier, market)` retorna um
registro local completo sem obter credencial nem acessar o Spotify. Se o registro
não existir ou `total_tracks` for nulo (legado/Ecos), obtém o token pelo supplier,
consulta o cliente de álbum e grava artista principal e álbum atomicamente.
A obtenção da credencial e a autorização Sonarfy serão integradas ao endpoint na
próxima entrega. Este serviço interno não concede acesso HTTP a dados.

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
sobre banco existente. A migration não será aplicada ao banco pessoal nesta entrega.

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

- Disponibilizar capa, nome, artista, lançamento e quantidade oficial de faixas.
- Expor média na escala 0–10 e total de avaliações; sem avaliações, média nula.
- Listar avaliações, inclusive sem texto, com identidade pública do autor e data.
- Ordenar por mais recentes, mais antigas, maior nota e menor nota, com paginação.
- Considerar apenas autores ativos e verificados na lista e nos agregados.
- Reutilizar o gerenciamento da avaliação própria já existente.

A tela representa notas como estrelas de 0–5, preservando o contrato 0–10.
Busca por nome, likes, comentários e notificações não integram esta entrega.
As rotas públicas e seus DTOs serão formalizados nas entregas correspondentes.

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
