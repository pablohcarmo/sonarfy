# API — Ecos

Contrato HTTP do recurso **Ecos**, que importa reproduções recentes efetivas do Spotify para a conta autenticada no Sonarfy.

- Ecos **não** representa músicas curtidas/salvas, biblioteca salva ou playlists.
- O Spotify disponibiliza apenas uma janela recente (cerca de 50 reproduções mais recentes, após mais de 30 segundos de reprodução). O Sonarfy acumula apenas o que cada sincronização captura, podendo haver lacunas se o usuário ouvir mais de ~50 faixas entre sincronizações.
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
