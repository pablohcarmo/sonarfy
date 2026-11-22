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
