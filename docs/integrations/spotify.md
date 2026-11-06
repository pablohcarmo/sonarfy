# Spotify — autorização, callback e troca de tokens

## Estado atual

O backend expõe a rota autenticada `POST /api/spotify/authorization`, monta a URL
de autorização e recebe o retorno em `GET /api/spotify/callback`, que valida o
state e troca o código por tokens. Os tokens ainda não são persistidos nem
retornados ao navegador; a conexão permanente de contas permanece pendente.

A rota exige `Authorization: Bearer <accessToken>`, não recebe `userId` e resolve
o usuário pelo e-mail do `Principal`. Retorna HTTP 200 com
`{"authorizationUrl":"https://accounts.spotify.com/authorize?..."}` e
`Cache-Control: no-store`. O frontend deve abrir essa URL. Credenciais ausentes
ou inválidas retornam 401; a quarta tentativa pendente do mesmo usuário retorna
429. A rota não faz chamadas HTTP ao Spotify.

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
`AuthSpotifyService.exchangeCode(code)` quando há autorização. O callback exige access token, refresh token, tipo Bearer e validade positiva. `SpotifyConnectionService` criptografa os tokens com AES-GCM e grava tipo, escopos e expiração em `Spotify_Connection`, vinculado ao usuário do state. Uma nova autorização atualiza a conexão existente e preserva `connected_at`.

| HTTP | Resultado |
| --- | --- |
| 204 | Troca concluída, sem corpo e com `Cache-Control: no-store` |
| 400 | Parâmetros inválidos ou state ausente, desconhecido, expirado ou consumido |
| 403 | Recusa do usuário ou conta excluída, inativa ou não verificada |
| 502 | Spotify rejeitou a troca (`REJECTED`) ou retornou dados de tokens inválidos |
| 503 | Troca indisponível (`UNAVAILABLE`) |
| 504 | Timeout na troca (`TIMEOUT`) |

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
Dados inválidos retornam 502 com a mensagem fixa
`Spotify returned an invalid token response.`, sem
expor tokens e sem restaurar o state. O refresh token é obrigatório para persistir
a conexão; o escopo pode estar ausente e a validade do token ainda depende de
uma chamada autorizada ao Spotify.

O 204 não carrega uma página nova no navegador. Ele confirma a troca e a
gravação da conexão. Renovação, desconexão, sincronização de escutas e
redirecionamento para o frontend ficam para outras entregas.

## Configuração

Antes de disponibilizar esta versão em um banco existente, aplique
`db/migrations/001_create_spotify_connection.sql` conforme o
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
| `SPOTIFY_REDIRECT_URI` | Endereço de callback cadastrado no painel do Spotify |
| `SPOTIFY_TOKEN_ENCRYPTION_KEY` | Chave Base64 aleatória de 32 bytes para AES-GCM |

Exemplo de URI local: `http://127.0.0.1:8080/api/spotify/callback`.
Ajuste a porta à aplicação e cadastre exatamente a mesma URI no painel do Spotify.
Não inclua credenciais reais ou tokens na documentação, no Git ou nos logs.
Configure `spotify_token_encryption_key=${SPOTIFY_TOKEN_ENCRYPTION_KEY}` em
`application.properties`; mantenha o valor da chave somente no ambiente local.

As propriedades são obrigatórias na execução. O construtor do service rejeita
valores em branco e verifica a estrutura da URI: exige HTTPS, permitindo HTTP
para `127.0.0.1` e `[::1]`, e rejeita `localhost`, credenciais embutidas,
fragmentos e portas inválidas. Essa validação não acessa a rede.
O Spotify verifica as credenciais e a correspondência da URI com seu cadastro.

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
callback sem Bearer, 204 sem corpo e com `no-store`, recusa, reutilização,
parâmetros inválidos, falhas 502/503/504 e proteção dos demais métodos e rotas.
Nesses testes, repositório e troca de tokens são simulados.

`SpotifyCallbackServiceTest` verifica o usuário vinculado ao state, entradas
inválidas sem consumo, usuário excluído/inativo/não verificado, recusa e tradução
das falhas. O state é real; banco e troca de tokens são simulados. Após sucesso,
recusa ou falha posterior ao consumo, outra chamada com o mesmo state é rejeitada.
Os testes de regressão incluem resposta nula, campos essenciais ausentes ou
inválidos, mensagem fixa de erro e retorno HTTP 502 sem reutilização do state
nem chamada à persistência. Para `expires_in`, aceitam 1 e 3.600 segundos e
rejeitam nulo, zero, negativo e valores acima de 3.600 segundos.

Os relatórios da execução ficam em `target/surefire-reports`; registre o resultado
correspondente à versão entregue na descrição do PR e consulte também a CI.
Validação desta entrega: `357` testes automatizados passaram, sem falhas.
Na aplicação local em execução, três verificações pelo Postman CLI confirmaram
400 sem parâmetros, 400 com state desconhecido e 401 no início sem Bearer.
O teste manual de recusa retornou 403 e, na repetição, 400.
O relatório de rede do navegador confirmou duas autorizações com Spotify real,
ambas com callback 204, corpo vazio e `Cache-Control: no-store`.
Essas evidências não validam persistência, renovação de tokens ou o fluxo completo
de conexão permanente, funcionalidades ainda não implementadas.

## Próximas entregas

- Integração do frontend à rota de início e apresentação do resultado do callback.
- Associação dos tokens ao usuário do Sonarfy, armazenamento, renovação
  e desconexão da conta.
- Consulta de álbuns e artistas em PR separado.

Referências: [Authorization Code Flow](https://developer.spotify.com/documentation/web-api/tutorials/code-flow)
e [Redirect URIs](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri).
