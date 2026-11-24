# Busca e seleção de álbum para avaliação

Status: backend implementado em 24/11/2026, sem deploy nesta entrega.
Client Credentials e buscas reais por álbum e artista validados com HTTP 200.
As rotas de detalhes e avaliações citadas já existem.

## Objetivo e autenticação

Uma conta Sonarfy ativa e verificada pode pesquisar um álbum por nome ou artista,
selecioná-lo e publicar uma avaliação sem conectar ou sincronizar uma conta Spotify.
Todas as chamadas exigem `Authorization: Bearer <accessToken>` do Sonarfy.
O backend usa Client Credentials da aplicação para o catálogo; não recebe token
Spotify do navegador nem consulta a conexão pessoal, escutas ou allowlist da conta.
O Ecos permanece independente e mantém suas restrições de autorização pessoal.

## Nova rota: `GET /api/albums/search`

Exemplo: `/api/albums/search?q=Daft%20Punk&limit=10&offset=0`.

| Parâmetro | Regra |
| --- | --- |
| `q` | Obrigatório, string com 2–100 pontos de código Unicode após trim de espaços Unicode; NUL rejeitado |
| `limit` | Inteiro entre 1 e 10; padrão 10 |
| `offset` | Inteiro entre 0 e 1000; padrão 0 |

Sem corpo de requisição. Não aceitar URL de provedor ou token como entrada.
O backend envia a consulta normalizada como `q` ao Spotify, com `type=album`
fixo e mercado `SPOTIFY_CATALOG_MARKET` (padrão `BR`). O cliente não escolhe
mercado nem outros tipos de resultado nesta entrega. A consulta é codificada
como parâmetro, sem concatenar URLs fornecidas pelo usuário.

A pesquisa usa a relevância do Spotify para o texto informado e retorna apenas
álbuns; pesquisar um artista não promete enumerar sua discografia inteira.
Não combina páginas do Spotify com resultados locais nem inventa resultados
em caso de falha do provedor. O catálogo local continua atendendo os detalhes
dos álbuns já conhecidos.

Resposta `200`, com `Cache-Control: no-store`:

```json
{
  "items": [
    {
      "spotifyId": "4aawyAB9vmqN3uQ7FjRGTy",
      "name": "Album One",
      "coverUrl": null,
      "artist": {"spotifyId": "0OdUWJ0sBjDrqHygGUXeCF", "name": "Artist One"},
      "releaseOn": "2024-05",
      "releasePrecision": "mes",
      "totalTracks": 12
    }
  ],
  "limit": 10,
  "offset": 0,
  "nextOffset": null
}
```

Exemplo ilustrativo, não uma resposta real do Spotify. `artist` é o primeiro
artista creditado no álbum, seguindo o catálogo existente. IDs de álbum têm
22 caracteres alfanuméricos. `coverUrl`, data/precisão e `totalTracks` podem ser
nulos, conforme as regras do [catálogo](albums.md). Nome, ID e artista são
obrigatórios; resposta incompatível do provedor produz 502, sem substituir
por fixtures ou expor o corpo externo. Não retornar IDs internos, tokens,
URLs externas de paginação, avaliações próprias ou agregados de comunidade.

Preservar a ordem do provedor e identificar resultados por `spotifyId`.
Edições diferentes com IDs diferentes permanecem separadas. Não persistir
resultados da busca, avaliações ou escutas. A seleção será resolvida pela rota
existente de detalhes, que valida os metadados e pode persistir o catálogo.

### Paginação

`nextOffset` é `offset + limit` somente quando o provedor indicar próxima
página, houver itens e o próximo offset não ultrapassar 1000; caso contrário,
é null. Sem resultados: `items: []` e `nextOffset: null`, com status 200.
Não devolver total global nesta entrega. Manter `q` e `limit` nas próximas
chamadas; alterar a pesquisa reinicia em zero. Não há snapshot entre páginas:
o catálogo e a relevância do Spotify podem mudar e repetir/omitir resultados.

### Erros

| Status | Significado |
| --- | --- |
| 400 | Consulta, limite ou offset inválido; sem chamada ao Spotify |
| 401 | Bearer Sonarfy ausente/inválido ou conta inelegível; sem chamada ao Spotify |
| 502 | Credencial da aplicação rejeitada, acesso ao catálogo recusado ou resposta externa incompatível |
| 503 | Quota/rate limit do Spotify ou indisponibilidade HTTP 5xx |
| 504 | Timeout no acesso ao provedor |

Em 503 por 429 do provedor, preservar `Retry-After` quando for um inteiro positivo
em segundos; não inventar prazo quando ausente/inválido. Mensagens são seguras,
sem corpo externo ou credenciais. Falha Spotify não retorna 401 Sonarfy nem
solicita OAuth pessoal. Reutilizar o cache de token de aplicação existente;
401 do provedor invalida somente o token utilizado, sem retry automático da busca.

## Selecionar e publicar: rotas existentes

1. Selecionar um `spotifyId` e consultar `GET /api/albums/{spotifyId}`.
   Esta chamada resolve/persiste o catálogo quando necessário, sem sincronizar.
   Usar os metadados dessa resposta como fonte para o álbum selecionado.
2. Consultar `GET /api/albums/{spotifyId}/reviews/me`. Um 404 significa ausência
   de avaliação própria depois que os detalhes foram resolvidos com sucesso.
3. Criar com `POST /api/albums/{spotifyId}/reviews`, ou editar a existente
   com `PUT /api/reviews/{reviewId}`. Não criar uma segunda avaliação.

O corpo permanece `{"rating":8.5,"reviewText":"Um álbum para revisitar."}`.
Nota obrigatória de 0 a 10 em passos de 0,5; texto opcional de até 255 pontos
de código Unicode após trim. Na interface, estrelas de 0 a 5 correspondem a
`rating / 2`. O autor vem do Bearer. Não aceitar metadados enviados pelo cliente
como catálogo confiável. Criação retorna 201 e `Location`; duplicata retorna
409 sem sobrescrever. Escrita continua local e não chama o Spotify.
As regras completas de substituição, exclusão e privacidade permanecem no
[contrato de avaliações](reviews.md).

## Critérios de aceite para implementação

- Conta sem conexão Spotify pesquisa e publica para um álbum selecionado.
- Nome de álbum e nome de artista retornam somente álbuns, com paginação limitada.
- Busca vazia e falha do provedor são estados diferentes; pesquisa não grava catálogo.
- Seleção resolve o catálogo; publicar não importa escutas e não inicia OAuth pessoal.
- Validações e autenticação falham antes do acesso externo; tokens não são expostos.
- Duplicata preserva a avaliação; edição reaproveita seu ID e as regras existentes.
- Validar contrato/erros com provedor simulado e ao menos uma busca real autorizada
  antes de declarar o fluxo disponível. Não alterar o frontend neste escopo.

Fontes externas: [Search](https://developer.spotify.com/documentation/web-api/reference/search)
e [Client Credentials](https://developer.spotify.com/documentation/web-api/tutorials/client-credentials-flow).
Limites locais e formato de resposta são decisões do Sonarfy.

## Evidências e limites da validação

Em 24/11/2026, Client Credentials da aplicação retornou 200; consultas
`Random Access Memories` e `Daft Punk`, com `type=album`, `market=BR`,
`limit=2` e `offset=0`, retornaram dois álbuns e indicação de próxima página.
Credenciais e token permaneceram somente em memória, sem logs ou artefatos.
Essa verificação direta confirma acesso ao catálogo; não comprova o fluxo
completo Sonarfy de busca, seleção e publicação em produção.

`AlbumSearchSimulationTest` exercita controller, serviço e Feign reais contra
a simulação Postman versionada, incluindo 401/403/404, 429, 5xx, JSON inválido,
metadados incompatíveis e timeout. A conta de teste não tem conexão Spotify.
`SecurityChainIntegrationTest` verifica Bearer ausente, inválido e válido.
`AlbumDetailsPostgresTest` passou com servidor HTTP, Bearer e PostgreSQL 17
descartável: busca sem persistência, seleção por detalhes, criação e duplicata
409 sem conexão Spotify pessoal. A coleção versionada passou nos cenários
de comunidade vazia e preenchida: 12 requisições e 25 assertions por execução.
A suíte padrão passou com 895 testes, zero falhas e 168 condicionais ignorados;
os testes PostgreSQL e da busca simulada foram ativados separadamente.
O smoke test Postman também passou contra o backend local e Spotify real,
com PostgreSQL exclusivo e conta sem conexão Spotify: 18 requisições e 35
assertions. Incluiu login normal, pesquisa por álbum/artista, próxima página,
seleção, criação, reabertura, duplicata sem sobrescrita, edição, exclusão da
avaliação de teste, consulta inválida e Bearer ausente/inválido.
Uma consulta aleatória pode encontrar resultados pela relevância do Spotify;
o cenário vazio usou filtros `album:` e `artist:` inexistentes.
O teste Postman de expiração natural também passou: login 200, TTL de 900
segundos e busca com o mesmo Bearer retornando 401 após 15 minutos; duas
requisições, três assertions, zero falhas e código de saída 0.
A interface foi validada separadamente pelo usuário, que confirmou busca e
publicação mesmo com permissão de escutas do Ecos pendente. Não houve deploy.

Coleções reproduzíveis (usar somente conta e banco de teste isolados):

- [Smoke test com Spotify real](sonarfy-album-search-smoke.postman_collection.json).
  Informar `baseUrl`, `authLogin` e `authPassword` localmente. O Bearer emitido
  pelo login permanece apenas nas variáveis da execução. A coleção cria,
  edita e exclui uma avaliação, portanto não usar uma conta com dados pessoais.
- [Expiração natural do Bearer](sonarfy-album-search-expiration.postman_collection.json).
  Aguarda o prazo real do token (15 minutos). Executar com
  `--timeout 950000 --timeout-script 930000 --no-report-events`.
