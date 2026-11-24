# Álbum e avaliações da comunidade

## Estado da implementação

Entrega 1: cliente interno de consulta ao Spotify. Ainda não há novas rotas HTTP
Sonarfy. Não há persistência, migration ou alteração do fluxo de avaliações.
Referência de catálogo: issue [#52](https://github.com/pablohcarmo/sonarfy/issues/52).

## Contrato interno entregue

`SpotifyAlbumService.fetch(accessToken, spotifyId, market)` consulta
`GET https://api.spotify.com/v1/albums/{id}` usando Bearer Spotify, distinto do
Bearer Sonarfy. O chamador fornece a credencial; este serviço não a obtém,
renova ou persiste. IDs devem conter 22 caracteres alfanuméricos; market é
opcional e aceita duas letras maiúsculas. Entradas inválidas são rejeitadas
antes de acessar a rede, com `IllegalArgumentException`.

Retorna `SpotifyAlbumResponseDto`: `id`, `name`, `type`, `albumType`,
`totalTracks`, `releaseDate`, `releaseDatePrecision`, `images` e `artists`.
As datas e sua precisão permanecem no formato do Spotify (`year`, `month`,
`day`); a normalização para persistência pertence à próxima entrega.
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

- Consultar catálogo local e buscar/persistir no Spotify quando faltar.
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
