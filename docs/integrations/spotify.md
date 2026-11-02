# Spotify — preparação da autorização e troca de tokens

## Estado atual

O backend monta a URL de autorização e prepara a troca de um código por tokens
por meio de `POST https://accounts.spotify.com/api/token`. Ainda não é possível
conectar contas pelo Sonarfy: a rota de início e o callback não estão implementados.

## Preparação da autorização

`SpotifyAuthorizationService.startAuthorization(userId)` registra uma tentativa
e retorna uma URI para `https://accounts.spotify.com/authorize`, com `response_type=code`,
`client_id`, `redirect_uri`, `scope=user-library-read` e `state`, codificados como
parâmetros. Não envia requisição HTTP nem inclui o Client Secret na URL.

`SpotifyAuthorizationStateService` gera states com 32 bytes de `SecureRandom`
e Base64 URL-safe sem padding. Guarda o vínculo com o usuário e uma expiração
de cinco minutos em memória, com limite de 1.000 tentativas por instância.
Registros vencidos são removidos ao criar uma nova tentativa; não há limpeza agendada.
Reiniciar a aplicação perde as tentativas e o armazenamento não é compartilhado
entre instâncias. O acesso ao mapa é sincronizado.

O ID deverá vir da identidade autenticada quando a rota for implementada;
o service atual só verifica se ele é positivo, sem consultar usuário ou autenticação.
A exposição da rota depende da [issue #22](https://github.com/pablohcarmo/sonarfy/issues/22).
O callback deverá validar a expiração e consumir o state uma única vez, operações
ainda não implementadas. Não registre o state ou a URL completa em logs.

## Configuração

Defina estas variáveis de ambiente na configuração de execução da aplicação
(no IntelliJ: **Run → Edit Configurations → Environment variables**):

| Variável | Descrição |
| --- | --- |
| `SPOTIFY_CLIENT_ID` | Identificador do aplicativo no Spotify Developer Dashboard |
| `SPOTIFY_CLIENT_SECRET` | Segredo do aplicativo, exclusivo do backend |
| `SPOTIFY_REDIRECT_URI` | Endereço de callback cadastrado no painel do Spotify |

Exemplo de URI local: `http://127.0.0.1:8081/api/spotify/callback`.
Esse exemplo não cria a rota; ela será implementada em uma próxima entrega.
Não inclua credenciais reais ou tokens na documentação, no Git ou nos logs.

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
Não há endpoint público funcional do Sonarfy para essa troca nesta fase.

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
O mapeamento dessas categorias para respostas HTTP será definido com o callback.

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

Os relatórios da execução ficam em `target/surefire-reports`; registre o resultado
correspondente à versão entregue na descrição do PR e consulte também a CI.
Os testes locais não comprovam autenticação com uma conta real nem o funcionamento
do fluxo completo de conexão.

## Próximas entregas

- Rota autenticada de início após a #22, redirecionamento e callback com validação e consumo de `state`.
- Associação dos tokens ao usuário do Sonarfy, armazenamento, renovação
  e desconexão da conta.
- Consulta de álbuns e artistas em PR separado.

Referências: [Authorization Code Flow](https://developer.spotify.com/documentation/web-api/tutorials/code-flow)
e [Redirect URIs](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri).
