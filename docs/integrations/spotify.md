# Spotify — troca de código por tokens

## Estado atual

O backend prepara a troca de um código de autorização por tokens por meio de
`POST https://accounts.spotify.com/api/token`. Esta entrega ainda não permite
conectar contas pelo Sonarfy: redirecionamento e callback não estão implementados.

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
- `AuthSpotifyClient`: envia o POST com `application/x-www-form-urlencoded`.
  A URL base padrão é `https://accounts.spotify.com`; o teste HTTP substitui
  a propriedade `spotify.auth-url` pelo endereço do servidor local simulado.
- `LoginSpotifyResponseDto`: mapeia `access_token`, `token_type`, `expires_in`, `scope`
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

`AuthSpotifyClientConfig` define 5 segundos para estabelecer a conexão e
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
`AuthSpotifyClientTest` usa Feign real e servidor HTTP local, em porta dinâmica,
para verificar caminho, método, cabeçalho, codificação do formulário,
conversão do JSON, propagação de uma resposta 400 e timeout de leitura quando
o servidor retém a resposta. O teste também verifica os dois limites configurados;
não simula timeout de conexão TCP.
Esses testes usam dados fictícios e não exigem credenciais do Spotify.

Na execução local de 02/11/2026, `mvn -o test` concluiu com 196 testes,
sem falhas, erros ou testes ignorados. Esse resultado não comprova autenticação
com uma conta real nem o funcionamento do fluxo completo de conexão.

## Próximas entregas

- Redirecionamento para autorização, callback e validação de `state`.
- Associação dos tokens ao usuário do Sonarfy, armazenamento, renovação
  e desconexão da conta.
- Consulta de álbuns e artistas em PR separado.

Referências: [Authorization Code Flow](https://developer.spotify.com/documentation/web-api/tutorials/code-flow)
e [Redirect URIs](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri).
