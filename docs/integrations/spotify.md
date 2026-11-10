# Spotify — autorização, callback e troca de tokens

## Estado atual

### Consulta, conexão e desconexão

`GET /api/spotify/connection` exige Bearer Sonarfy e retorna HTTP 200 com
`status` (`CONNECTED` ou `DISCONNECTED`) e `connectedAt` (nulo sem conexão).
Conectado significa credenciais armazenadas: o vencimento do access token não
produz `REAUTHORIZATION_REQUIRED` nesta etapa. Nenhum token é exposto.

`DELETE /api/spotify/connection` exige o mesmo Bearer e retorna HTTP 204 sem
corpo, inclusive se já desconectado. As duas rotas usam `Cache-Control: no-store`
e resolvem a identidade autenticada, ignorando um `userId` fornecido pelo cliente.
A desconexão remove as credenciais locais; a revogação da permissão na conta do
Spotify é uma ação separada do usuário. Ecos e eventos de integração ainda não
estão implementados neste fluxo.

Cada state guarda a versão de autorização do usuário. Desconectar bloqueia sua
linha, incrementa `spotify_authorization_version` e exclui a conexão na mesma
transação. O callback confere a versão original sob o mesmo bloqueio antes de
gravar: tentativa anterior resulta em `spotify=invalid_state` no redirecionamento e
não recria credenciais. O serviço recarrega o usuário sob bloqueio para evitar usar
uma versão em cache no request. Uma nova autorização usa a versão atual. States
antigos permanecem sujeitos ao prazo e aos limites de memória; sua versão impede a
gravação após desconectar.

Antes do deploy em banco existente, aplicar a migração
`db/migrations/002_add_spotify_authorization_version.sql` (ver guia de persistência).
Renovação automática e sincronização seguem pendentes.

O backend expõe a rota autenticada `POST /api/spotify/authorization`, monta a URL
de autorização e recebe o retorno do navegador em `GET /api/spotify/callback`, que
valida o state, troca o código por tokens, persiste a conexão vinculada ao usuário e
redireciona (`HTTP 302 Found`, `Cache-Control: no-store`) para a URL configurada em
`SPOTIFY_FRONTEND_REDIRECT_URI` acrescentando `?spotify=<resultado>`. Access token e
refresh token são armazenados cifrados com AES-GCM e nunca são retornados ao navegador.
Reconexões atualizam a mesma conexão e preservam `connected_at`. A migração deve ser
aplicada antes do deploy em bancos existentes; renovação automática e sincronização
continuam pendentes.

A rota de início exige `Authorization: Bearer <accessToken>`, não recebe `userId` e resolve
o usuário pelo e-mail do `Principal`. Retorna HTTP 200 com
`{"authorizationUrl":"https://accounts.spotify.com/authorize?..."}` e
`Cache-Control: no-store`. O frontend deve redirecionar o navegador para essa URL.
Credenciais ausentes ou inválidas retornam 401; a quarta tentativa pendente do mesmo
usuário retorna 429. A rota não faz chamadas HTTP ao Spotify.

## Contrato de integração do frontend

Como o fluxo OAuth 2.0 envolve redirecionamento de página inteira para o provedor
externo (`accounts.spotify.com`), o frontend integra-se aos quatro endpoints abaixo:

### 1. Consultar estado da conexão (`GET /api/spotify/connection`)
- **Requisição:** `GET /api/spotify/connection` com cabeçalho `Authorization: Bearer <accessToken>`.
- **Resposta (`200 OK`, `Cache-Control: no-store`):**
  ```json
  {
    "status": "CONNECTED",
    "connectedAt": "2026-11-10T03:00:00Z"
  }
  ```
  Quando não há conexão ativa, retorna `{"status":"DISCONNECTED","connectedAt":null}`.
  *(Nota: até a implementação da renovação automática, `CONNECTED` indica que há credenciais cifradas persistidas para o usuário, e `REAUTHORIZATION_REQUIRED` ainda não é emitido).*

### 2. Iniciar autorização (`POST /api/spotify/authorization`)
- **Requisição:** `POST /api/spotify/authorization` com cabeçalho `Authorization: Bearer <accessToken>`, sem corpo.
- **Resposta (`200 OK`, `Cache-Control: no-store`):**
  ```json
  {
    "authorizationUrl": "https://accounts.spotify.com/authorize?response_type=code&client_id=...&redirect_uri=...&scope=user-library-read&state=..."
  }
  ```
- **Ação no frontend:** navegar para a URL recebida (ex.: `window.location.assign(data.authorizationUrl)`). Não abrir via `fetch`/`XHR`, pois o usuário precisa interagir com a página de consentimento do Spotify.
- **Erros previstos:** `401 Unauthorized` (Bearer ausente/inválido), `403 Forbidden` (conta inativa/não verificada) ou `429 Too Many Requests` (mais de 3 autorizações pendentes para o mesmo usuário nos últimos 5 minutos).

### 3. Apresentar o resultado do callback (`GET /api/spotify/callback` → Redirecionamento `302`)
Após o consentimento ou recusa no Spotify, o navegador é enviado a `GET /api/spotify/callback`. O backend processa a resposta e responde com `HTTP 302 Found` (`Cache-Control: no-store`, sem corpo) redirecionando o navegador para `SPOTIFY_FRONTEND_REDIRECT_URI` com o parâmetro de query `spotify`:

| Valor de `?spotify=` | Significado funcional | Ação recomendada no frontend |
| --- | --- | --- |
| `connected` | Código trocado e tokens persistidos com sucesso | Exibir confirmação de conta conectada e consultar `GET /api/spotify/connection` para atualizar `connectedAt` na tela |
| `denied` | Usuário recusou a autorização no Spotify (`error=access_denied`) | Informar que a conexão foi cancelada pelo usuário e manter botão de conectar disponível |
| `invalid_state` | Parâmetros ausentes/inválidos, `state` expirado/reutilizado ou autorização invalidada por desconexão concorrente | Informar que a tentativa de autorização expirou ou é inválida e oferecer iniciar nova tentativa |
| `error` | Conta inelegível, falha de comunicação/validação com o Spotify ou erro interno ao salvar a conexão | Exibir mensagem de indisponibilidade temporária e permitir tentar novamente |

Após ler o parâmetro `spotify` na inicialização da página, recomenda-se removê-lo da barra de endereços com `window.history.replaceState` para evitar reexibir o aviso ao recarregar a tela.

### 4. Desconectar conta (`DELETE /api/spotify/connection`)
- **Requisição:** `DELETE /api/spotify/connection` com cabeçalho `Authorization: Bearer <accessToken>`.
- **Resposta:** `204 No Content` (`Cache-Control: no-store`), idempotente mesmo se a conta já estiver desconectada.
- **Aviso obrigatório na interface do usuário:** o frontend deve informar claramente que desconectar remove as credenciais salvas no Sonarfy, mas que a revogação da permissão concedida ao aplicativo na conta Spotify é uma ação separada no painel de aplicativos conectados do próprio Spotify.

## Preparação da autorização

`SpotifyAuthorizationService.startAuthorization(userId)` registra uma tentativa
e retorna uma URI para `https://accounts.spotify.com/authorize`, com `response_type=code`,
`client_id`, `redirect_uri`, `scope=user-library-read` e `state`, codificados como
parâmetros. Não envia requisição HTTP nem inclui o Client Secret na URL.

`SpotifyAuthorizationStateService` gera states com 32 bytes de `SecureRandom`
e Base64 URL-safe sem padding. Guarda o vínculo com o usuário e uma expiração
de cinco minutos em memória, com limite de três tentativas por usuário e
1.000 tentativas por instância.
Registros vencidos são removidos ao criar uma nova tentativa; não há limpeza agendada.
Reiniciar a aplicação perde as tentativas e o armazenamento não é compartilhado
entre instâncias. O acesso ao mapa é sincronizado.

O controller obtém o ID pela identidade autenticada; o service verifica se ele
é positivo e controla os limites no mesmo método sincronizado.
`consume(state)` remove a tentativa e verifica a validade, retornando o ID
associado. State ausente, desconhecido, expirado ou já utilizado resulta em 400.
A operação é sincronizada: duas chamadas não podem consumir o mesmo state com
sucesso. No instante exato da expiração, a tentativa já é inválida. O consumo
libera uma vaga no limite de tentativas pendentes do usuário.
Não registre códigos, states, tokens ou a URL completa em logs.

## Callback

`GET /api/spotify/callback` recebe os parâmetros de query `state` e exatamente
um resultado: `code` preenchido ou `error=access_denied`. Parâmetros ausentes,
em branco ou contraditórios, sem um resultado válido, e erros desconhecidos
retornam 400 antes de consumir o state.

Somente o GET desse caminho é público; o redirecionamento do navegador não
envia o Bearer do Sonarfy. A rota de início continua exigindo autenticação.
O callback não recebe `userId` nem depende de `Principal`: a identidade vem
do state registrado no início da autorização.

`SpotifyCallbackService` consome o state, verifica se o usuário ainda existe,
está ativo e com e-mail confirmado, trata a recusa e reutiliza
`AuthSpotifyService.exchangeCode(code)` quando há autorização. Callback e serviço
de persistência usam `SpotifyTokenResponseValidator` para exigir access token,
refresh token, tipo Bearer e validade de 1 a 3.600 segundos. `SpotifyConnectionService`
criptografa os tokens com AES-GCM e grava tipo, escopos e expiração em
`spotify_connection`, vinculado ao usuário do state. Uma nova autorização atualiza
a conexão existente e preserva `connected_at`.

| Desfecho interno (`SpotifyCallbackService`) | Redirecionamento HTTP (`SpotifyController`) | Condição |
| --- | --- | --- |
| Sucesso | `302 Found` → `?spotify=connected` | Troca concluída e tokens cifrados persistidos, sem corpo e com `Cache-Control: no-store` |
| `400 Bad Request` | `302 Found` → `?spotify=invalid_state` | Parâmetros inválidos, state ausente/desconhecido/expirado/consumido ou versão de autorização invalidada por desconexão |
| `403 Forbidden` (`access_denied`) | `302 Found` → `?spotify=denied` | Usuário elegível recusou o consentimento no Spotify |
| `403 Forbidden` (conta inelegível) | `302 Found` → `?spotify=error` | Conta excluída, inativa ou não verificada |
| `502` / `503` / `504` / falha de persistência | `302 Found` → `?spotify=error` | Spotify rejeitou a troca (`REJECTED`), retornou tokens inválidos, ficou indisponível (`UNAVAILABLE`), excedeu o tempo (`TIMEOUT`) ou ocorreu falha de banco/cifragem |

O state é consumido antes da verificação do usuário e da troca de tokens,
inclusive no caso de recusa. Falhas posteriores não restauram a tentativa:
é necessário iniciar outra autorização. Não há repetição automática da troca.
Uma rejeição do Spotify também pode indicar configuração incorreta do backend.
Mesmo uma resposta HTTP de sucesso precisa conter um DTO não nulo,
`access_token` preenchido, `token_type=Bearer` (sem distinção de maiúsculas) e
`expires_in` entre 1 e 3.600 segundos, inclusive. Esse limite corresponde à
validade usual de uma hora do access token; não representa a expiração da
conexão nem do refresh token. Valores acima de uma hora são tratados como
resposta inválida, mesmo que possam ser representados como data.
No `SpotifyCallbackService.handleCallback`, dados inválidos lançam `502 Bad Gateway` com a mensagem fixa
`Spotify returned an invalid token response.`, que o controller converte no
redirecionamento `?spotify=error`, sem expor tokens e sem restaurar o state.
O refresh token é obrigatório para persistir a conexão; o escopo pode estar
ausente e a validade do token ainda depende de uma chamada autorizada ao Spotify.

O redirecionamento `302 Found` devolve o navegador à tela configurada em
`SPOTIFY_FRONTEND_REDIRECT_URI` preservando eventuais query params fixos dessa URI
e substituindo apenas `spotify`. Parâmetros de redirecionamento enviados pelo
cliente na requisição de callback são ignorados. Renovação automática e
sincronização de escutas ficam para outras entregas.

Falhas de banco ou cifragem durante a persistência também são traduzidas no
endpoint HTTP para `?spotify=error`, sem repetição automática. O state permanece
consumido e o usuário precisa iniciar outra autorização. Não há ainda métricas ou
logs específicos desse caminho.

## Configuração

Antes de disponibilizar esta versão em um banco existente, aplique as migrações
`db/migrations/001_create_spotify_connection.sql` e
`db/migrations/002_add_spotify_authorization_version.sql`, nesta ordem, conforme o
[guia de persistência](../persistence.md#inicialização-e-atualização-do-banco).
Com `ddl-auto=none`, a aplicação não cria `spotify_connection` automaticamente.
Para um banco vazio, use o schema completo em `src/main/infra/tables.sql`.
O Compose permanece local e não é versionado.

Defina estas variáveis de ambiente na configuração de execução da aplicação
(no IntelliJ: **Run → Edit Configurations → Environment variables**):

| Variável | Descrição |
| --- | --- |
| `SPOTIFY_CLIENT_ID` | Identificador do aplicativo no Spotify Developer Dashboard |
| `SPOTIFY_CLIENT_SECRET` | Segredo do aplicativo, exclusivo do backend |
| `SPOTIFY_REDIRECT_URI` | Endereço de callback do backend cadastrado no painel do Spotify |
| `SPOTIFY_FRONTEND_REDIRECT_URI` | Endereço da página do frontend para onde `GET /api/spotify/callback` redireciona com `?spotify=<resultado>` |
| `SPOTIFY_TOKEN_ENCRYPTION_KEY` | Chave Base64 aleatória de 32 bytes para AES-GCM |
| `SPOTIFY_TOKEN_ENCRYPTION_KEYRING` | Entradas separadas por vírgula no formato `keyId=Base64Key`; manter chaves antigas enquanto dados ou backups dependerem delas |
| `SPOTIFY_TOKEN_ENCRYPTION_ACTIVE_KEY_ID` | ID da chave para novas cifras; vazio mantém gravação legada `v1` durante rollout |
| `SPOTIFY_TOKEN_ENCRYPTION_ROTATION_ENABLED` | Habilita o processo operacional explícito; padrão `false` |
| `SPOTIFY_TOKEN_ENCRYPTION_ROTATION_BATCH_SIZE` | Quantidade máxima de conexões por transação; padrão `100` |

Exemplo de URI local do callback no backend: `http://127.0.0.1:8080/api/spotify/callback`.
Exemplo de URI local de destino no frontend: `http://localhost:3000/settings/spotify`.
Ajuste a porta à aplicação e cadastre exatamente `SPOTIFY_REDIRECT_URI` no painel do Spotify.
Não inclua credenciais reais ou tokens na documentação, no Git ou nos logs.
Configure `spotify_token_encryption_key=${SPOTIFY_TOKEN_ENCRYPTION_KEY}` em
`application.properties`; mantenha o valor da chave somente no ambiente local.

Preserve cada chave no gerenciador de segredos do ambiente e no procedimento de
recuperação. A chave nunca deve aparecer em documentação, Git ou logs. Os IDs de
chave não são secretos; as entradas do chaveiro usam `id=Base64Key`, separadas por
vírgula. IDs aceitam letras, números, ponto e hífen. Cada chave deve
decodificar para 32 bytes.

O formato legado `v1:` continua sendo lido com `SPOTIFY_TOKEN_ENCRYPTION_KEY`.
Novas cifras usam `v2:<keyId>:<Base64(IV + ciphertext + tag)>` quando
`SPOTIFY_TOKEN_ENCRYPTION_ACTIVE_KEY_ID` está configurado. O `keyId` permite
selecionar a chave de leitura; o AAD autentica formato, ID, usuário e finalidade
do token. A identificação fica no próprio envelope, sem alteração de schema.

### Procedimento de rotação

1. Implantar a versão compatível com leitura de `v1` e `v2`, mantendo o ID ativo
   vazio para que todas as instâncias continuem gravando `v1` durante o rollout.
2. Configurar o chaveiro com a chave antiga sob o ID `legacy` e a chave nova sob
   um ID novo. Depois que todas as instâncias suportarem os dois formatos,
   configurar `SPOTIFY_TOKEN_ENCRYPTION_ACTIVE_KEY_ID` com o ID novo. Novas
   conexões passam a gravar `v2`.
3. Executar o reprocessamento em processo sem servidor HTTP, usando a mesma
   versão dual-read e acesso ao banco do ambiente:

   ```powershell
   java -jar sonarfy.jar --spring.main.web-application-type=none --spotify_token_encryption_rotation_enabled=true --spotify_token_encryption_rotation_batch_size=100
   ```

   Cada lote é transacional e pode ser repetido após uma interrupção. A consulta
   bloqueia as conexões selecionadas; os tokens de acesso e de atualização são
   recriptografados juntos. O processo não altera `connected_at` nem `updated_at`.
   Os logs mostram apenas contagens por formato/ID, nunca chaves ou tokens.
4. Conferir que a contagem de `v1:legacy` e das chaves antigas chegou a zero.
   Manter as chaves necessárias enquanto houver backups restauráveis que
   contenham cifras dependentes delas. Só então removê-las do chaveiro.

O processo seleciona apenas valores fora do formato/ID ativo, então novas
execuções continuam do trabalho restante. O relatório de uso é feito por valor
cifrado (access e refresh contam separadamente). Nenhuma migração de banco é
necessária.

### Rollback

Durante a transição, manter a versão dual-read e todas as chaves requeridas.
Para voltar a uma versão antiga que só leia `v1`, configurar o ID ativo vazio e
executar novamente o processo sem HTTP até a contagem `v2:*` ser zero. A chave
legada deve continuar disponível. Não fazer rollback para uma chave comprometida;
nesse caso, corrigir a configuração e concluir a rotação para uma chave segura.

As propriedades são obrigatórias na execução. O construtor de `AuthSpotifyService`
rejeita valores em branco e verifica a estrutura de `SPOTIFY_REDIRECT_URI`: exige
HTTPS, permitindo HTTP apenas para `127.0.0.1` e `[::1]`, e rejeita `localhost`,
credenciais embutidas, fragmentos e portas inválidas. O construtor de
`SpotifyController` valida `SPOTIFY_FRONTEND_REDIRECT_URI` exigindo HTTPS (ou HTTP
em hosts loopback `localhost`, `127.0.0.1` e `[::1]`), sem credenciais embutidas,
fragmentos ou portas inválidas. Essas validações não acessam a rede.
O Spotify verifica as credenciais e a correspondência de `SPOTIFY_REDIRECT_URI` com seu cadastro.

## Componentes e contrato

- `AuthSpotifyService`: valida a configuração e o preenchimento do código,
  prepara Basic Auth e o formulário de troca.
- `SpotifyTokenClient`: envia o POST com `application/x-www-form-urlencoded`.
  A URL base padrão é `https://accounts.spotify.com`; o teste HTTP substitui
  a propriedade `spotify.auth-url` pelo endereço do servidor local simulado.
- `SpotifyTokenResponseDto`: mapeia `access_token`, `token_type`, `expires_in`, `scope`
  e `refresh_token` com `@JsonNaming`. Seu `toString()` oculta os tokens.

O formulário contém `grant_type=authorization_code`, `code` e `redirect_uri`.
Client ID e Secret são enviados no cabeçalho de autenticação Basic, sob HTTPS.
A troca é executada pelo callback após a validação do state e do usuário.

Código nulo ou em branco gera `ResponseStatusException` com status 400 antes
da chamada ao client. O service traduz falhas do Feign para `SpotifyIntegrationException`:
`REJECTED` para HTTP 4xx (exceto 429), `UNAVAILABLE` para 429, erros do servidor,
falhas de conexão e demais falhas do Feign, e `TIMEOUT` para causas de timeout.
O status de erro HTTP tem prioridade, inclusive quando `Retry-After` faz o Feign
criar uma `RetryableException`. A classificação de timeout é usada na ausência
de status de erro HTTP; não há reenvio automático do código.
As mensagens são fixas e não carregam a exceção externa como causa, corpo ou
credenciais. Rejeição não significa necessariamente código expirado ou inválido.
O client mantém as exceções do Feign; a tradução ocorre apenas no service.
O callback traduz essas categorias para os status HTTP descritos acima.

`SpotifyTokenClientConfig` define 5 segundos para estabelecer a conexão e
5 segundos para leitura da resposta, exclusivamente neste client. A configuração
é versionada e não depende do `application.properties` local. Esses limites não
representam um prazo total da operação. O Spring Cloud OpenFeign não faz novas
tentativas automaticamente por padrão, inclusive após timeout na troca do código.

## Testes e limites da validação

Execute a suíte completa na raiz do repositório:

```bash
./mvnw test
```

No PowerShell:

```powershell
.\mvnw.cmd test
```

`AuthSpotifyServiceTest` cobre credenciais ausentes, URIs aceitas e rejeitadas,
código ausente, argumentos enviados ao client simulado e tradução das falhas externas,
incluindo ausência de detalhes sensíveis nas mensagens e stack traces resultantes.
`SpotifyTokenClientTest` usa Feign real e servidor HTTP local, em porta dinâmica,
para verificar caminho, método, cabeçalho, codificação do formulário,
conversão do JSON, propagação de uma resposta 400 e timeout de leitura quando
o servidor retém a resposta. O teste também verifica os dois limites configurados;
não simula timeout de conexão TCP.
Esses testes usam dados fictícios e não exigem credenciais do Spotify.

`SpotifyAuthorizationServiceTest` verifica os parâmetros e a codificação da URL.
`SpotifyAuthorizationStateServiceTest` cobre associação ao usuário, formato e geração
de states distintos, limite de capacidade, limpeza no vencimento e inicialização
Spring sem bean `Clock`. A expiração é testada com relógio controlado, sem espera real.

`SpotifyControllerTest` usa a cadeia Bearer e os serviços reais, com repositório
simulado, para verificar 401, resposta com URL e `no-store`, vínculo do state à
identidade autenticada mesmo com `userId` enviado pelo cliente, 429 na quarta
tentativa e independência entre usuários. O teste do state verifica também a
liberação do limite por usuário após expiração, consumo único, preservação de
outras tentativas e consumo concorrente. Os testes HTTP do controller incluem
callback sem Bearer, redirecionamento `302 Found` sem corpo e com `no-store` para
`?spotify=connected`, `?spotify=denied`, `?spotify=invalid_state` e `?spotify=error`,
validação de `SPOTIFY_FRONTEND_REDIRECT_URI` na inicialização, bloqueio de
redirecionamento aberto e proteção dos demais métodos e rotas.
Nesses testes, repositório e troca de tokens são simulados.

`SpotifyCallbackServiceTest` verifica o usuário vinculado ao state, entradas
inválidas sem consumo, usuário excluído/inativo/não verificado, recusa, tradução
das falhas e mapeamento para os desfechos de redirecionamento (`resolveRedirectOutcome`).
O state é real; banco e troca de tokens são simulados. Após sucesso,
recusa ou falha posterior ao consumo, outra chamada com o mesmo state é rejeitada.
Os testes de regressão incluem resposta nula, campos essenciais ausentes ou
inválidos, mensagem fixa de erro e retorno HTTP 502 sem reutilização do state
nem chamada à persistência. Para `expires_in`, aceitam 1 e 3.600 segundos e
rejeitam nulo, zero, negativo e valores acima de 3.600 segundos.

`SpotifyConnectionServiceTest` verifica chamadas diretas ao serviço, incluindo
rejeição antes de acessar banco ou cifragem e persistência dos limites de validade.
Os testes do callback também verificam que falhas de banco ou cifragem não
restauram o state nem repetem a troca de tokens.

`ExistingSchemaIntegrationTest` usa PostgreSQL com geração de schema desativada
para verificar tokens cifrados, reconexão com escopos ausentes, preservação de
`connected_at` e concorrência. Continua opt-in localmente, mas a CI executa essa
classe explicitamente no mesmo job `test`, após importar o schema versionado e
aplicar a migração em um banco descartável. Uma falha nessa etapa falha o job.
As instruções de execução local estão no [guia de persistência](../persistence.md).

Os relatórios da execução ficam em `target/surefire-reports`; registre o resultado
correspondente à versão entregue na descrição do PR e consulte também a CI.
Em 10/11/2026, `.\mvnw.cmd test` executou 420 testes: zero falhas, zero erros e
quatro ignorados (`BUILD SUCCESS`). A suíte padrão usa H2; os testes PostgreSQL
opt-in não rodam nessa chamada e são executados separadamente pela CI.

Em 08/11/2026, o teste manual contra a aplicação local e o Spotify real
confirmou: estado inicial `DISCONNECTED`; autorização concluída no navegador;
estado `CONNECTED`; desconexão com HTTP 204; estado `DISCONNECTED`; nova
autorização; e estado final `CONNECTED`. Esse teste valida conexão, consulta,
desconexão e reconexão reais. Não valida renovação automática nem o frontend.
Os testes locais cobrem a desconexão com controle de versão e callbacks pendentes;
a persistência e concorrência são cobertas pelo teste PostgreSQL opt-in da CI.

## Próximas entregas

- Renovação automática dos tokens e transição para `REAUTHORIZATION_REQUIRED` quando aplicável.
- Tratamento do limite global de autorizações pendentes (issue #34).
- Sincronização de escutas e consulta de dados musicais.
- Observabilidade de falhas de persistência.
- Consulta de álbuns e artistas em PR separado.

Referências: [Authorization Code Flow](https://developer.spotify.com/documentation/web-api/tutorials/code-flow)
e [Redirect URIs](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri).
