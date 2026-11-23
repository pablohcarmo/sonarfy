# API — Ecos

Contrato HTTP do recurso **Ecos**, que importa reproduções recentes efetivas do Spotify para a conta autenticada no Sonarfy.

- Ecos **não** representa músicas curtidas/salvas, biblioteca salva ou playlists.
- O Spotify disponibiliza apenas uma janela recente (limitada ao histórico retornado pelo provedor). O Sonarfy acumula apenas o que cada sincronização captura, podendo haver lacunas se o usuário ouvir mais de ~50 faixas entre sincronizações.
- Iniciar ou pular uma faixa não garante sua presença na próxima sincronização: o Sonarfy importa os eventos disponibilizados pelo Spotify. Não há regra local de duração mínima nem exigência local de concluir a faixa. Veja a [validação manual](../integrations/echoes.md#escutas-puladas-e-concluidas--teste-manual-em-23112026).
- Nesta fase, Ecos é **privado ao usuário autenticado** (sem rotas públicas ou por `{targetId}`).

---

## `POST /api/echoes/sync`

Dispara manualmente a sincronização das reproduções recentes do Spotify para o usuário autenticado.

- **Autenticação:** `Authorization: Bearer <accessToken>` (`purpose: "access"`). Qualquer `userId` enviado fora do token é ignorado.
- **Cabeçalhos de resposta:** `Cache-Control: no-store`.
- **Rate limit após a primeira página:** mantém `200` com `complete: false`,
  preserva as escutas obtidas e inclui `Retry-After`. O cooldown persistido
  respeita o maior prazo entre 60 segundos e o informado pelo Spotify;
  novas tentativas antes desse prazo retornam `429`, sem consultar o provedor.
- **Corpo da requisição:** vazio.

### Resposta `200 OK`

```json
{
  "fetched": 50,
  "imported": 12,
  "alreadyKnown": 37,
  "skipped": 1,
  "complete": true,
  "possibleGap": false,
  "newestPlayedAt": "2026-11-21T22:58:01.123Z",
  "syncedAt": "2026-11-21T23:00:00Z"
}
```

| Campo | Descrição |
| --- | --- |
| `fetched` | Total de itens recebidos do Spotify nesta execução (até 3 páginas de 50) |
| `imported` | Escutas novas gravadas em `Listens` |
| `alreadyKnown` | Escutas válidas já existentes (deduplicadas) |
| `skipped` | Itens ignorados (`is_local`, não-`track`, sem álbum ou campos inválidos) |
| `complete` | `true` se todas as páginas necessárias terminaram sem falha; `false` em falha parcial após a página 1 |
| `possibleGap` | `true` se já havia histórico e a busca terminou sem alcançar a marca d'água anterior |
| `newestPlayedAt` | Timestamp da escuta válida mais recente do lote (`null` se não houver) |
| `syncedAt` | Instante UTC da sincronização |

### Códigos de erro

| Status | Condição | Cabeçalhos extras |
| --- | --- | --- |
| `401` | Bearer ausente, inválido ou expirado; conta já inativa ou não verificada | — |
| `403` | Conta tornou-se inelegível após passar pelo filtro de autenticação | — |
| `409` | Spotify desconectado, em `REAUTHORIZATION_REQUIRED`, sem escopo, rejeitado após refresh, autorização alterada ou reserva expirada/substituída | — |
| `429` | Cooldown ativo (60 s após sucesso) ou outra sincronização em andamento para o usuário | `Retry-After` (segundos) |
| `502` | Spotify retornou `403` (ex.: conta fora da allowlist em modo de desenvolvimento) ou rejeitou refresh | — |
| `503` | Spotify retornou `429`, `5xx` ou ficou indisponível | `Retry-After` (preserva o prazo positivo informado pelo Spotify) |
| `504` | Timeout na comunicação com o Spotify | — |

A janela de até três páginas é relida mesmo ao encontrar escutas já conhecidas.
Isso permite recuperar páginas que falharam em uma tentativa anterior enquanto
ainda estiverem disponíveis no Spotify; a deduplicação ocorre no banco.

---

## `GET /api/echoes?limit=20&cursor=<opaco>`

Lista as escutas já importadas do usuário autenticado em ordem decrescente (`played_at DESC, listen_id DESC`), usando paginação keyset com cursor opaco, assinado (HMAC-SHA256), versionado e vinculado ao usuário autenticado.

- **Autenticação:** `Authorization: Bearer <accessToken>` (`purpose: "access"`). Qualquer `userId`, `handle` ou parâmetro semelhante enviado pelo cliente é ignorado. Contas inativas ou não verificadas são rejeitadas com `401`.
- **Cabeçalhos de resposta:** `Cache-Control: no-store`.
- **Sem efeitos colaterais:** não aciona o Spotify, não sincroniza dados e não cria registro em `echo_sync_state` quando ausente.
- **Após sincronizar:** não há snapshot imutável entre requisições; após `POST /api/echoes/sync`, o frontend deve reiniciar a listagem sem `cursor`.
- **CORS:** `Retry-After` é exposto em `Access-Control-Expose-Headers` para leitura cross-origin (incluindo `200` parcial, `429` e `503`).

### Parâmetros de query

| Parâmetro | Obrigatório | Padrão | Regras |
| --- | --- | --- | --- |
| `limit` | Não | `20` | Inteiro entre `1` e `50` |
| `cursor` | Não | `null` | Cursor opaco retornado em `nextCursor` da página anterior |

### Resposta `200 OK`

```json
{
  "items": [
    {
      "id": 101,
      "playedAt": "2026-11-22T19:00:00.123456Z",
      "track": {
        "spotifyId": "4uLU6hMCjMI75M1A2tKUQC",
        "name": "Never Gonna Give You Up",
        "durationMs": 213573,
        "explicit": false
      },
      "artists": [
        {
          "spotifyId": "0gxyHStUsqpMadRV0Di1Qt",
          "name": "Rick Astley"
        }
      ],
      "album": {
        "spotifyId": "5Z9iiGl2FcIfa3BMiv6OIw",
        "name": "Whenever You Need Somebody",
        "coverUrl": "https://i.scdn.co/image/ab67616d00001e02..."
      }
    }
  ],
  "nextCursor": "djEuNDIuMTc5NTM3NDAwMC4xMjM0NTYwMDAuMTAxLnNpZw",
  "sync": {
    "lastSucceededAt": "2026-11-22T19:05:00Z",
    "historyStartsAt": "2026-11-20T14:10:00Z",
    "nextAllowedAt": "2026-11-22T19:06:00Z"
  }
}
```

- Histórico vazio retorna `200` com `"items": []` e `"nextCursor": null`.
- Para continuar, envie o `nextCursor` recebido como parâmetro `cursor`, sem
  alterá-lo e mantendo o mesmo usuário autenticado. O cursor do exemplo é
  ilustrativo e não deve ser usado em requisições.
- Pare quando `nextCursor` for `null`. Enviar `cursor=` vazio ou o texto
  `cursor=null` retorna `400`; para reiniciar, remova o parâmetro `cursor`.
- `coverUrl`, `durationMs`, `lastSucceededAt`, `historyStartsAt` e `nextAllowedAt` podem ser `null`.
- `historyStartsAt` representa apenas o instante mais antigo já capturado pelo Sonarfy (nunca o início completo do histórico no Spotify).
- Escutas importadas permanecem disponíveis mesmo após desconectar o Spotify ou em `REAUTHORIZATION_REQUIRED`.

### Códigos de erro

| Status | Condição |
| --- | --- |
| `400` | `limit` fora de `1..50` ou não inteiro; `cursor` malformado, em branco, adulterado, incompatível ou pertencente a outro usuário |
| `401` | Bearer ausente, inválido, expirado ou conta inativa/não verificada |


## Agrupamento global por álbum — issue #83

### `GET /api/echoes/albums?limit=20&cursor=<opaco>`

Retorna álbuns distintos de todo o histórico importado do usuário autenticado.
Exige Bearer access e conta ativa/verificada; responde com `Cache-Control: no-store`.
Não consulta o Spotify nem altera estado. `limit` conta álbuns, padrão 20, entre 1 e 50.

```json
{
  "items": [{
    "album": {"spotifyId": "album-id", "name": "Bad (Remastered)", "coverUrl": null},
    "lastPlayedAt": "2026-11-23T14:43:00Z",
    "listenCount": 3,
    "rated": false,
    "reviewId": null
  }],
  "nextCursor": null,
  "sync": {"lastSucceededAt": null, "historyStartsAt": null, "nextAllowedAt": null}
}
```

A contagem inclui todas as escutas importadas desse álbum para o usuário, inclusive
fora da página de detalhes. Edições com IDs distintos do Spotify permanecem separadas.
A ordem usa `(played_at DESC, listen_id DESC)` da mesma escuta mais recente de cada
álbum. O agrupamento ocorre antes do filtro de cursor. Não se combinam máximos
independentes. Com histórico inalterado, percorrer as páginas não repete nem omite álbuns.

### Estado da avaliação própria

Cada item inclui `rated` (booleano) e `reviewId` (ID da avaliação própria ou null).
Sem avaliação: `"rated": false, "reviewId": null`; com avaliação:
`"rated": true, "reviewId": 42`. O ID segue o contrato de [avaliações](reviews.md).
Uma nota zero também significa avaliado; não derive o estado do valor da nota ou
da existência de texto. Avaliações de terceiros não alteram nem expõem esse estado.

A próxima consulta após criar ou excluir uma avaliação reflete o estado confirmado
no banco. Editar mantém `rated: true` e o mesmo `reviewId`. Consultas simultâneas
a uma mutação podem observar o estado anterior ao commit; recarregue após receber
sucesso. Não é necessário sincronizar Spotify. Desconexão não apaga avaliações.

O estado é obtido com LEFT JOIN após o agrupamento, usando a constraint única
`(user_id, album_id)` já existente. Não há N+1, migration, mudança na contagem,
ordenação ou no formato/escopo dos cursores. Os novos campos são aditivos;
`GET /api/echoes` e o histórico de escutas por álbum mantêm seus formatos.

### `GET /api/echoes/albums/{spotifyId}/listens?limit=20&cursor=<opaco>`

Retorna escutas individuais do álbum indicado por seu ID do Spotify, no mesmo formato
`EchoesPageResponseDto` de `GET /api/echoes`, com artistas ordenados por faixa.
Mantém reproduções distintas da mesma faixa e ordena por `(played_at DESC, listen_id DESC)`.
`limit` conta escutas, padrão 20, entre 1 e 50. álbum inexistente ou sem escutas do usuário
retorna `200` com `items: []` e `nextCursor: null`, sem revelar histórico de outras contas.
Autenticação, ausência de efeitos colaterais e cabeçalhos seguem a consulta de álbuns.

### Cursores e erros das novas consultas

Cursores são opacos, assinados, versionados e vinculados ao usuário e à consulta.
O cursor do histórico também é vinculado ao álbum. Não intercambiar cursores entre
álbuns, listagem agrupada e `GET /api/echoes`. Cursor vazio, inválido, adulterado ou
incompatível e limites inválidos retornam `400`; autenticação inválida ou conta
inativa/não verificada retorna `401`.

Histórico vazio retorna `items: []` e `nextCursor: null`. Não há snapshot entre
requisições: novas importações podem mover álbuns para antes de um cursor já emitido
e alterar contagens. Após sincronizar, descartar as páginas e os cursores, inclusive
os históricos expandidos, e reiniciar. Encerrar a paginação quando `nextCursor` for nulo.
O contrato original de `GET /api/echoes` permanece preservado.
