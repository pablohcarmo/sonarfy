# Integração — Ecos

Documenta a arquitetura da sincronização e consulta de escutas recentes do Spotify
e o guia de integração para o frontend.

## Automação da consulta em 23/11/2026

`EchoQueryPostgresTest` executa a cadeia real de segurança, controller e consulta
com duas contas de fixture em PostgreSQL descartável. O setup valida o schema
versionado, sem `create-drop`, e não recebe a conexão do banco da aplicação.
Foram automatizados: autenticação ausente/de propósito incorreto, conta
inativa/não verificada, limites e cursores inválidos, isolamento entre dois
históricos não vazios, cursor de outra conta, leitura após desconectar e
paginação completa de 51 escutas em 26 páginas de tamanho dois, incluindo
empates de horário, sem repetição de IDs e com término em `nextCursor: null`.

Execução direcionada: `EchoCursorCodecTest,EchoesControllerTest,EchoQueryPostgresTest`,
com `-Dsonarfy.test.echoes-postgres=true`, `-DforkCount=0` e
`-Dspring.test.context.cache.maxSize=4`: 19 testes executados, zero falhas/erros.
A CI já inclui explicitamente `EchoQueryPostgresTest` no passo PostgreSQL de
Ecos. Isso registra a configuração de execução contínua, não um resultado remoto.
Esses testes usam credenciais de fixture e não consultam o Spotify real.

A suíte completa, com `-Dsonarfy.test.echoes-postgres=true` e os mesmos limites de
memória, passou: 806 reportados, 718 executados, 88 ignorados, zero falhas/erros.
As suítes opt-in de simulação e outros domínios permaneceram desabilitadas nessa
execução; testes ignorados não contam como executados.

Para repetir, prepare `db/tables.sql` em um PostgreSQL descartável com banco
local dedicado `sonarfy_echoes_validation` e configure `SONARFY_ECHOES_TEST_JDBC_URL`,
`SONARFY_TEST_DB_USER` e `SONARFY_TEST_DB_PASSWORD` apenas no ambiente do processo.
Execute `.\mvnw.cmd test -Dtest=EchoCursorCodecTest,EchoesControllerTest,EchoQueryPostgresTest -Dsonarfy.test.echoes-postgres=true`.

### Revalidação da sincronização após a consulta em 23/11/2026

O contexto de `EchoSyncPostgresSimulationTest` registra um mock de
`EchoQueryService`, nova dependência do controller que não participa dos cenários
de sincronização. A segurança, o serviço de sincronização, o writer e o cliente
Feign continuam reais, com PostgreSQL temporário e simulação Postman.

A execução direcionada com `-Dtest=EchoSyncPostgresSimulationTest` e
`-Dsonarfy.test.echoes-sync-e2e=true` passou: seis executados, zero falhas/erros.
A suíte completa repetida com `sonarfy.test.echoes-postgres`,
`sonarfy.test.echoes-sync-e2e` e `sonarfy.test.spotify-api-sim` habilitados passou:
807 reportados, 725 executados, 82 ignorados, zero falhas/erros. Foram mantidos
`-DforkCount=0` e `-Dspring.test.context.cache.maxSize=4` por memória local.
O resultado é local, sem chamadas ao Spotify real ou validação remota desta correção.

## Arquitetura e garantias de concorrência

1. **Sem transação aberta durante chamadas HTTP:** `EchoSyncService.sync` não usa `@Transactional` no método principal. Ele adquire um lease/cooldown curto em `echo_sync_state`, obtém a credencial via `SpotifyAccessTokenService.getValidCredential` (`REQUIRES_NEW`) e libera bloqueios antes de consultar `GET /v1/me/player/recently-played`.
2. **Paginação e marca d'água:** relê até 3 páginas de 50 itens usando exclusivamente `limit` e `before`, mesmo após encontrar escutas conhecidas. `MAX(played_at)` serve apenas ao indicador de possível lacuna. Se uma página posterior falhar, persiste o lote com `complete: false`; a próxima tentativa relê a janela para recuperar itens ainda disponíveis.
3. **Refresh único em `401`:** a primeira página permite uma renovação e repetição. Nova rejeição marca `REAUTHORIZATION_REQUIRED` apenas se ID, geração e versão Spotify do usuário continuarem atuais e o usuário estiver elegível, retornando `409`.
4. **Guarda contra autorização antiga:** o writer bloqueia usuário e conexão, nessa ordem, e confere elegibilidade, ID, geração, versão Spotify, status e escopo antes de gravar com deduplicação.
5. **Privacidade:** nenhum token, cursor externo ou corpo de erro do Spotify é retornado ou registrado em log. Escutas já importadas permanecem no Sonarfy após desconexão do Spotify.

## Integração no frontend

- Em `GET /api/spotify/connection`: quando `status === "CONNECTED"` e `recentListensAuthorized === false`, orientar o usuário a reconectar o Spotify via `POST /api/spotify/authorization` para conceder `user-read-recently-played`.
- Para `POST /api/echoes/sync` (`credentials: "omit"`, `cache: "no-store"`):
  - `200`: atualizar estado/contagem e sinalizar se `possibleGap === true` ou `complete === false`. Se houver `Retry-After`, aguardar o prazo mesmo nessa resposta parcial; ele também é aplicado pelo backend. Depois de sincronizar, reiniciar a listagem `GET /api/echoes` sem `cursor`.
  - `409`: consultar `GET /api/spotify/connection` para distinguir `DISCONNECTED`, `REAUTHORIZATION_REQUIRED` ou `recentListensAuthorized: false`.
  - `429` ou `503`: ler `Retry-After` (exposto via CORS em `Access-Control-Expose-Headers`) e exibir mensagem amigável de aguardo.
  - `502` ou `504`: exibir mensagem amigável de indisponibilidade temporária sem detalhes técnicos.
- Para `GET /api/echoes?limit=20&cursor=...` (`credentials: "omit"`, `cache: "no-store"`):
  - `200`: renderizar `items` usando `item.id` como chave estável; paginar passando `nextCursor` enquanto não for `null`; exibir `sync.lastSucceededAt` e `sync.historyStartsAt` com o aviso de que Ecos acumula apenas o que foi sincronizado a partir das reproduções recentes.
  - Se `sync.nextAllowedAt` estiver no futuro, o botão "Sincronizar" pode respeitar o cooldown antes mesmo de nova chamada.
  - `400` (cursor inválido/expirado): reiniciar a listagem a partir da primeira página sem `cursor`.

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
Se uma página posterior responder `429`, o lote obtido é mantido, a resposta
parcial `200` inclui `Retry-After` e `next_allowed_at` recebe o maior prazo entre
o cooldown normal e o informado pelo Spotify, contado conservadoramente a
partir da persistência. Uma nova chamada antecipada recebe `429` sem novo acesso ao Spotify.

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

## Regressão de rate limit parcial em 22/11/2026

Na revisão posterior do rate limit em páginas seguintes, os 16 testes
direcionados passaram, incluindo a regressão pela cadeia HTTP real, PostgreSQL
temporário e simulação Postman: 50 escutas preservadas, `200` parcial com
`Retry-After: 900`, prazo persistido e tentativa antecipada bloqueada com `429`.
A suíte completa repetida passou com 793 testes reportados, 711 executados,
82 ignorados e nenhuma falha ou erro, com as mesmas propriedades e limites
de memória descritos acima. Essa regressão é automatizada, sem provocar rate limit no Spotify real.

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


## Integração do agrupamento global — #83

A listagem deve consumir `GET /api/echoes/albums`, renderizando diretamente um card
por `album.spotifyId`, `lastPlayedAt` e `listenCount`. Não executar agrupamento global
sobre as páginas de escutas do endpoint legado.

Ao expandir, consultar `GET /api/echoes/albums/{spotifyId}/listens` e manter seu cursor
separado por álbum. A contagem do card vem do backend e não do número de detalhes
carregados. Preservar o horário de cada reprodução e permitir carregar mais detalhes.
A consulta agrupada fornece nome, capa e ID do álbum; não fornece artistas no resumo.
Artistas continuam disponíveis por escuta no histórico expandido.

Após sincronizar, limpar páginas e cursores de ambas as consultas e carregar novamente.
Não há snapshot imutável durante importações concorrentes. A ausência de repetição/omissão
entre páginas é garantida com histórico inalterado. O frontend permanece somente leitura
nesta entrega e sua adaptação exige escopo autorizado próprio.

A consulta utiliza as relações existentes Listens → Tracks → Albums, sem migração.
Testes PostgreSQL incluem escutas intercaladas, empates, edições distintas, contagens,
paginação de detalhes e rejeição de cursores de outro contexto.


### Validacao local da issue #83

- PostgreSQL 17 descartavel, em porta dedicada, preparado com `db/tables.sql`.
- Direcionados: `EchoCursorCodecTest,EchoesControllerTest,EchoQueryPostgresTest`,
  com `-Dsonarfy.test.echoes-postgres=true -DforkCount=0`: 21 executados, zero falhas/erros.
- Suite completa: `.\mvnw.cmd test -Dsonarfy.test.echoes-postgres=true -DforkCount=0 -Dspring.test.context.cache.maxSize=4`:
  808 reportados, 720 executados, 88 ignorados, zero falhas/erros.
- Colecao Postman validada como JSON; novas requisicoes nao executadas contra ambiente remoto.
- Nao houve benchmark com volume de producao ou validacao visual do frontend.
  Avaliar plano de execucao com volume representativo antes de propor indices adicionais.


## Escutas puladas e concluidas - teste manual em 23/11/2026

O usuario relatou ter aberto varios albuns e pulado as faixas. Ao sincronizar,
essas escutas nao apareceram em Ecos. Depois de ouvir faixas completas e
sincronizar novamente, os registros apareceram.

Esse resultado registra o comportamento observado pelo usuario no teste manual;
nao estabelece uma exigencia universal do Spotify de ouvir a faixa inteira nem
um limiar garantido em segundos. Nao foram capturadas as respostas HTTP dessa
sessao para comparar os eventos recebidos do provedor.

O backend importa os eventos retornados pelo historico recente do Spotify.
Nao ha filtro local por tempo efetivamente ouvido nem por conclusao da faixa.
A duracao cadastrada da musica nao representa o tempo que o usuario ouviu.
Por isso, iniciar ou pular faixas nao deve ser usado isoladamente como prova de
falha na sincronizacao ou no agrupamento. Se houver divergencia, comparar a
resposta de sincronizacao (fetched, imported, alreadyKnown, skipped, complete e
newestPlayedAt) com a consulta de albuns executada em seguida, sem expor tokens.


## Estado Avaliar/Avaliado

`GET /api/echoes/albums` agora inclui `rated` e `reviewId` em cada item. Preserve
os dois campos no adapter e no estado da página; mostre Avaliado quando rated for
true, sem desabilitar o acesso à edição. Após criar/editar/excluir, revalide a lista
ao retornar à página, inclusive por histórico do navegador. Nenhum novo sync com
Spotify é necessário. Regras e pontos concretos de integração estão em
[avaliações](reviews.md#estado-e-invalidação). O frontend permanece somente leitura.

## CI simulation cleanup - PR #84

The CI log from 23/11/2026 showed EADDRINUSE on 127.0.0.1:55442 when
starting the Echoes sync simulation after the Spotify API simulation.
The preceding PostgreSQL Echoes step passed all 14 tests. The sync end-to-end
step failed before Maven started; this was not an assertion failure.

All three simulation steps now use test-support/ci-simulation.sh. The helper
records the actual session leader, sends TERM to its process group, then KILL
to remaining workers before waiting for the launcher. The original exit status
is preserved. No process is selected by a shared port or a global name match.

The CI first runs test-support/test-ci-simulation.sh against pinned Postman CLI
1.85.0: start, probe, stop and reuse port 55442 after exit statuses 0, 7 and 0.
This passed locally in a Linux container with Node 22, including a fixture
whose parent exits on TERM while its HTTP child deliberately ignores it. The original cleanup
also released the port in that local run, so the exact runner-specific trigger
was not reproduced. The new test verifies port reuse and exit-status retention;
remote Java CI approval still requires a successful run after publication.
