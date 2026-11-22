# Integração — Ecos

Documenta a arquitetura da sincronização de escutas recentes do Spotify (`POST /api/echoes/sync`) e o guia de integração para o frontend.

## Arquitetura e garantias de concorrência

1. **Sem transação aberta durante chamadas HTTP:** `EchoSyncService.sync` não usa `@Transactional` no método principal. Ele adquire um lease/cooldown curto em `echo_sync_state`, obtém a credencial via `SpotifyAccessTokenService.getValidCredential` (`REQUIRES_NEW`) e libera bloqueios antes de consultar `GET /v1/me/player/recently-played`.
2. **Paginação e marca d'água:** relê até 3 páginas de 50 itens usando exclusivamente `limit` e `before`, mesmo após encontrar escutas conhecidas. `MAX(played_at)` serve apenas ao indicador de possível lacuna. Se uma página posterior falhar, persiste o lote com `complete: false`; a próxima tentativa relê a janela para recuperar itens ainda disponíveis.
3. **Refresh único em `401`:** a primeira página permite uma renovação e repetição. Nova rejeição marca `REAUTHORIZATION_REQUIRED` apenas se ID, geração e versão Spotify do usuário continuarem atuais e o usuário estiver elegível, retornando `409`.
4. **Guarda contra autorização antiga:** o writer bloqueia usuário e conexão, nessa ordem, e confere elegibilidade, ID, geração, versão Spotify, status e escopo antes de gravar com deduplicação.
5. **Privacidade:** nenhum token, cursor externo ou corpo de erro do Spotify é retornado ou registrado em log. Escutas já importadas permanecem no Sonarfy após desconexão do Spotify.

## Integração no frontend

- Em `GET /api/spotify/connection`: quando `status === "CONNECTED"` e `recentListensAuthorized === false`, orientar o usuário a reconectar o Spotify via `POST /api/spotify/authorization` para conceder `user-read-recently-played`.
- Para `POST /api/echoes/sync` (`credentials: "omit"`, `cache: "no-store"`):
  - `200`: atualizar estado/contagem e sinalizar se `possibleGap === true` ou `complete === false`.
  - `409`: consultar `GET /api/spotify/connection` para distinguir `DISCONNECTED`, `REAUTHORIZATION_REQUIRED` ou `recentListensAuthorized: false`.
  - `429` ou `503`: ler `Retry-After` (quando presente) e exibir mensagem amigável de aguardo.
  - `502` ou `504`: exibir mensagem amigável de indisponibilidade temporária sem detalhes técnicos.

## Reserva e deploy

Aplicar `013_add_echo_sync_lease_id.sql` antes desta versão (após `012`).
No banco local `localhost:5433/sonarfy`, `012` e `013` foram aplicadas em
22/11/2026 com autorização do usuário, backup e conferência de preservação dos
dados existentes. A `011` já estava presente e não foi reaplicada.
O lease dura 90 segundos e possui UUID por execução. A finalização bloqueia
usuário e estado, verifica posse e validade e grava escutas e cooldown na mesma
transação. Execuções antigas não gravam nem removem a reserva de outra execução.
Chamadas HTTP ficam fora dessa transação; uma execução vencida retorna `409`.
O `Retry-After` do Spotify é preservado sem teto de 300 segundos.

## Validação local em 22/11/2026

Foram executados testes unitários, MockMvc com a cadeia real de segurança e
PostgreSQL 17 temporário com cliente Feign contra simulação Postman. As regressões
cobrem recuperação após falha parcial, lease vencido/substituído, rejeição de
versão Spotify antiga e preservação de `Retry-After: 900`.

A suíte completa passou com 792 testes reportados, 710 executados, 82 ignorados,
zero falhas e erros. Foram habilitadas `sonarfy.test.echoes-sync-e2e`,
`sonarfy.test.echoes-postgres` e `sonarfy.test.spotify-api-sim`. Por limitação de
memória local, a execução utilizou `-DforkCount=0` e
`-Dspring.test.context.cache.maxSize=4`, sem alterar a configuração versionada.
Nessa execução automatizada não houve chamada ao Spotify real nem validação
desta versão na CI remota. A validação manual posterior está registrada abaixo.

## Validação manual confirmada pelo usuário em 22/11/2026

Executada pelo usuário via Postman com Spotify real e contas de teste Sonarfy,
após aplicação local das migrations. Evidências relatadas na conversa e capturas
das consultas ao banco, sem registrar credenciais:

| Cenário | Resultado confirmado |
| --- | --- |
| Primeira sincronização | `200`, 50 recebidas, 50 importadas, zero já conhecidas ou ignoradas |
| Repetição dentro de 60 segundos | `429` |
| Repetição após o cooldown | `200`, zero importadas, 50 já conhecidas |
| Nova reprodução disponível no Spotify | `200`, uma importada, 49 já conhecidas; total acumulado de 51 |
| Desconectar e reconectar | `200`, zero importadas e 50 já conhecidas |
| Sincronizar sem Spotify conectado | `409` |
| Preservação após desconectar | Contagem no banco confirmou as 51 escutas anteriores |
| Outra conta Sonarfy sem conexão | `409` |
| Outra conta Sonarfy usando a mesma conta Spotify | `200`, 50 importadas; consulta agrupada confirmou 51 escutas na primeira conta e 50 na segunda |

As respostas bem-sucedidas relatadas apresentaram `complete: true`,
`possibleGap: false` e nenhum item ignorado. A janela retornada foi de 50 itens;
esse teste não demonstra paginação real além dessa janela, falhas do provedor
ou concorrência manual. Tais cenários possuem validação automatizada conforme
descrito acima; o resultado da CI remota permanece uma evidência separada.
