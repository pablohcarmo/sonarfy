# Login, access token e renovação de sessão

Contrato de login e acesso Bearer da [issue #22](https://github.com/pablohcarmo/sonarfy/issues/22).
Base local dos exemplos: `http://localhost:8080`; ajuste para seu ambiente.
Use uma conta de teste cadastrada, ativa e com o e-mail confirmado.

## Login

`POST /api/auth/login` — público, `Content-Type: application/json`,
com cabeçalho obrigatório `X-Sonarfy-CSRF: 1`.

O corpo é representado por `LoginDto`:

```json
{
  "login": "conta-teste@example.com",
  "password": "SenhaDeTeste123!",
  "rememberMe": false
}
```

O campo `login` aceita e-mail ou handle, incluindo handle com `@` inicial.
Por exemplo, `"login": "pablo"` e `"login": "@pablo"` consultam o handle `pablo`.
A busca ignora maiúsculas e minúsculas, e espaços nas extremidades do login
são removidos. A senha é comparada com BCrypt, sem remover espaços ou alterar
maiúsculas e minúsculas.

Ambos os campos são obrigatórios e não podem ser vazios ou conter somente espaços.
`rememberMe` é um booleano opcional e assume `false` quando omitido. A preferência
é persistida na sessão e preservada durante todas as rotações.
O login exige que a conta esteja ativa e que o e-mail tenha sido confirmado.
No cadastro persistido, `User.onCreate()` define `active=true` por `@PrePersist`.
A confirmação altera `verified`; não é necessário atualizar o banco manualmente.
O cadastro também preenche `displayName` com nome e sobrenome, removendo espaços
nas extremidades de cada parte e limitando o resultado aos primeiros 60 caracteres
Unicode. Esse valor é persistido em `users.display_name`, obrigatório no banco.
O campo é gerado pelo backend nesta etapa; não foi acrescentado aos DTOs de entrada
ou resposta. Veja a [documentação de persistência](../persistence.md).
Para respeitar o limite do BCrypt, o serviço recusa senhas acima de 72 bytes em
UTF-8 com o mesmo `401` genérico, sem truncá-las para autenticar o usuário.
O cadastro também rejeita senhas acima desse limite com `400`, antes de criar
a conta, mantendo o mínimo de oito caracteres já exigido pela entrada de cadastro.

Exemplo com curl (shell Bash):

```bash
curl -i -X POST 'http://localhost:8080/api/auth/login' \
  -H 'Content-Type: application/json' \
  -H 'X-Sonarfy-CSRF: 1' -c cookies.txt \
  -d '{"login":"conta-teste@example.com","password":"SenhaDeTeste123!"}'
```

## Resposta de sucesso

HTTP `200 OK`, `Cache-Control: no-store`, cookie de refresh e JSON representado por `LoginResponseDto`:

```json
{
  "accessToken": "SUBSTITUA_PELO_JWT_RETORNADO",
  "tokenType": "Bearer",
  "expiresInSeconds": 900
}
```

`expiresInSeconds` é um número e informa a validade de 15 minutos a partir da
emissão. A resposta não inclui a senha. O token é um JWT assinado com HMAC256,
com `iss: "sonarfy-api"`, ID do usuário como string em `sub`, `purpose: "access"`,
data de emissão em `iat` e expiração em `exp`.

O propósito `access` distingue essa credencial dos tokens `email_confirmation`,
`pending_registration`, `password_reset` e `password_change`. O token de acesso
é rejeitado pelos validadores existentes desses outros fluxos.

## Erros

Login e refresh exigem `X-Sonarfy-CSRF: 1`, inclusive sem Origin. Ausência ou
valor incorreto, ou Origin presente fora da lista permitida, resultam em `403`,
sem emitir/rotacionar tokens nem modificar cookies.

| Situação | HTTP | Comportamento |
| --- | --- | --- |
| Login ou senha ausente, vazio ou somente espaços | `400` | Entrada rejeitada antes de consultar as credenciais |
| JSON ausente ou malformado | `400` | Requisição rejeitada |
| Usuário inexistente, senha incorreta, conta inativa ou e-mail não confirmado | `401` | Mesmo motivo genérico: `Invalid login or password.`; nenhum token emitido |

O motivo genérico é definido por `ResponseStatusException`; esta entrega não
define um formato próprio de JSON de erro nem garante que o motivo apareça no
corpo da resposta em todas as configurações. O cliente deve tratar o status `401`
de forma uniforme, sem distinguir existência da conta ou situação da ativação.

## Acesso às rotas protegidas

Após o login, envie o token no cabeçalho de cada requisição:

```bash
curl -i 'http://localhost:8080/api/users/me' \
  -H 'Authorization: Bearer SUBSTITUA_PELO_TOKEN_DE_ACESSO'
```

O filtro exige assinatura e emissor válidos, `purpose: "access"`, `exp` presente
e ainda válido e `sub` representando um ID positivo compatível com `long`.
O usuário é consultado novamente, com sua permissão, e precisa continuar ativo
e verificado. Sua identidade é estabelecida no `Principal`; `getName()` retorna
o e-mail. `/api/users/me` retorna `200` com o cadastro correspondente.

Credencial ausente, inválida, expirada, de outro propósito ou vinculada a usuário
removido, inativo ou não verificado resulta em `401` nas rotas protegidas.
O filtro não cria sessão: cada nova requisição precisa enviar seu Bearer.
Troca e recuperação de senha revogam todas as sessões de refresh do usuário.
O access token já emitido continua sujeito à expiração e à situação atual da conta.

`/api/auth/update-pending-email` continua validando seu Bearer de cadastro no
controller; esse token não autentica acesso a `/api/users/**`.

## Rotas públicas e CORS

O `SecurityConfig` permite acesso sem login somente aos pares de método e caminho:

| Método | Caminho |
| --- | --- |
| POST | `/api/auth/register` |
| POST | `/api/auth/login` |
| POST | `/api/auth/refresh` |
| POST | `/api/auth/resend-activation-email` |
| POST | `/api/auth/forgot-password` |
| POST | `/api/auth/reset-password` |
| GET | `/api/auth/verify` |
| PATCH | `/api/auth/update-pending-email` |

Esses endpoints mantêm suas validações de entrada e tokens específicos. Outros
caminhos e métodos exigem autenticação, inclusive novos endpoints sob `/api/auth/`.
Despachos internos `ERROR` são permitidos para preservar as respostas de erro;
isso não libera o acesso direto anônimo a `/error`.

O CORS é processado antes da autenticação com a política do `CorsConfig`:
origens exatas configuradas por `AUTH_ALLOWED_ORIGINS`, métodos GET, POST, PUT,
PATCH, DELETE e OPTIONS, cabeçalhos permitidos por `*` e credenciais habilitadas.
Wildcards, caminhos, query strings e credenciais na URL de origem são recusados.
O padrão local inclui `http://localhost:63342` e `http://localhost:8080`.
Em produção, configurar explicitamente as origens HTTPS. O caminho
`/sonarfy-frontend/pages` de `app.frontend-url` não faz parte da origem.

O cabeçalho CSRF obrigatório impede formulários simples; chamadas entre origens
dependem do preflight restrito. SameSite e STATELESS não substituem essa proteção.
O navegador deve usar `credentials: "include"` em login e refresh. Em HTTP local,
definir explicitamente `AUTH_REFRESH_COOKIE_SECURE=false`; o padrão é `true` e
produção exige HTTPS. API e frontend devem estar no mesmo site; portas diferentes
de localhost funcionam, mas não misture localhost e 127.0.0.1. Sites distintos
não são suportados por este contrato.
Um preflight válido com `Access-Control-Request-Headers: Authorization` recebe
`200` sem Bearer. A requisição real à rota protegida continua exigindo o token.
OPTIONS sem os cabeçalhos de preflight não recebe uma liberação genérica.
Um método fora da política CORS, como TRACE, tem o preflight recusado com `403`.

Os testes de login integram controller, serviço, BCrypt e JWT com repositório
simulado; não comprovam acesso autenticado pela cadeia de segurança HTTP.
Um teste adicional em `EmailReservationIntegrationTest` cobre cadastro pelo
controller, persistência JPA, login recusado antes da confirmação, confirmação
com o token do e-mail e login aceito, sem alterar flags diretamente no banco.
O banco padrão desse teste é H2; renderização e transporte de e-mail são simulados.
Essa cobertura inclui cadastro e login com senhas de exatamente 72 bytes e
rejeição do cadastro acima desse limite, com ASCII e caracteres multibyte.
Veja também o [contrato de recuperação e troca de senha](passwords.md).

`SecurityChainIntegrationTest` usa a cadeia real do Spring Security, controllers,
serviços, BCrypt, JWT e persistência JPA em H2, com e-mail simulado. Cobre login
seguido de acesso ao próprio perfil, rejeição de credenciais e contas inválidas,
ausência de sessão e preservação do Bearer de cadastro na correção de e-mail.
Também cobre a lista de rotas públicas, métodos não permitidos anonimamente,
despachos de erro, preflight e respostas CORS em requisições autenticadas e anônimas.
Os testes de claims conferem a assinatura dos tokens e incluem um controle positivo
assinado com a chave configurada no contexto, evitando rejeições por chave divergente.


## Persistência de refresh tokens

A camada interna armazena hashes e histórico por sessão de dispositivo.
Reutilização encerra somente a sessão afetada; logout interno reconhece tokens
consumidos. O resultado da renovação não retorna entidades JPA.
Login emite refresh por cookie e `/refresh` rotaciona a sessão. `/logout` HTTP
fica para a próxima entrega. A validade é de 30 dias por inatividade, sem prazo
absoluto. Troca autenticada e recuperação de senha revogam todas as sessões
de refresh do usuário na mesma transação da atualização da senha.
Access tokens existentes não são revogados por essa camada.

A preferência `remember_me` já é persistida internamente por sessão e preservada
nas rotações. A emissão interna sem preferência e os registros migrados assumem
`false`. Ambos os valores mantêm 30 dias por inatividade, sem prazo absoluto.
`RotationDto` inclui a preferência para configurar o cookie, mas não é retornado
por HTTP. A resposta pública continua sendo `LoginResponseDto`. Ambos ocultam o
token em `toString()`; não registrar bodies de autenticação, senhas ou cabeçalhos
Authorization, Cookie e Set-Cookie. HttpOnly reduz o acesso ao refresh por
scripts, mas não impede que um XSS faça chamadas autenticadas.

## Cookie e lembrar-me

O refresh opaco é entregue somente no cookie `sonarfy_refresh`, com atributos
`HttpOnly; Secure; SameSite=Strict; Path=/api/auth`, sem `Domain`. Nunca aparece
no JSON; somente seu hash é armazenado no banco. Com `rememberMe=false`, o cookie
não define `Max-Age` nem `Expires`; com `true`, define `Max-Age=2592000`, renovado
a cada rotação. Ambos mantêm 30 dias por inatividade no servidor, sem prazo
absoluto. Restauração do navegador pode preservar cookies de sessão; fechar o
navegador não garante revogação. Manter o access token em memória no cliente.

Cada login cria uma sessão independente e não revoga outros dispositivos nem o
cookie anterior substituído no navegador. Login recusado não modifica o cookie.

## Refresh

`POST /api/auth/refresh`, sem corpo ou Bearer obrigatório. Enviar o cookie e
`X-Sonarfy-CSRF: 1`. Um Bearer inválido/expirado eventualmente enviado é ignorado
nesse endpoint e no POST de login. As outras rotas mantêm a validação existente.

Sucesso: `200`, mesmo JSON do login, `Cache-Control: no-store` e cookie rotacionado
com a preferência original. O token anterior fica consumido. Exemplo Bash:

```bash
curl -i -X POST 'http://localhost:8080/api/auth/refresh' \
  -H 'X-Sonarfy-CSRF: 1' -b cookies.txt -c cookies.txt
```

Cookie ausente, desconhecido, expirado, consumido ou conta inelegível: `401`
sem credenciais no corpo e cookie removido com `Max-Age=0`, mesmo caminho e
atributos. Falhas de infraestrutura retornam `5xx`, sem limpar o cookie nem
prometer repetição segura. Se a rotação foi confirmada mas a resposta se perdeu,
repetir o token anterior encerra a sessão. Não repetir refresh automaticamente.

Coordenar chamadas e abas: reutilização revoga a sessão afetada e seus sucessores,
preservando outros dispositivos. Duas renovações simultâneas do mesmo token
podem encerrar a sessão. Veja o [guia de integração](../integrations/authentication.md).

`SecurityChainIntegrationTest` cobre os atributos e a preferência do cookie,
rotação com Bearer inválido, uso do JWT renovado, limpeza no 401, replay com
isolamento entre dispositivos e proteção CSRF antes de mutações.

## Evidências desta entrega (11/11/2026)

- `./mvnw.cmd test`: 474 testes reportados, zero falhas/erros e 18 ignorados.
  H2 isolado; inclui proteção em caminhos codificados e configuração Secure.
- Coleção local Postman v3: lint com 12 itens, sem erros ou avisos. Execução
  sequencial de login e refresh: duas requisições, seis asserções aprovadas.
- A coleção importável em `docs/api/sonarfy-authentication.postman_collection.json`
  foi executada separadamente: duas requisições e seis asserções aprovadas.
  Ambas usaram `--no-report-events` e API real local em porta 18080, com cadeia
  de segurança, serviços e JPA, H2 descartável e transporte de e-mail simulado.
- Edge headless com perfil isolado: oito verificações aprovadas para login,
  cookie HttpOnly, Set-Cookie inacessível ao JavaScript, credentials, refresh,
  ausência de refresh no JSON e rejeição CSRF sem consumir a sessão. Uma nona
  verificação confirmou bloqueio de origem fora da lista. As origens locais de
  teste usaram portas 18081 (permitida) e 18082 (recusada).
- A validação HTTP não criou migrations, não alterou o banco da aplicação nem
  chamou o Spotify. As migrations 004 e 005 foram aplicadas posteriormente no
  banco local ativo, mediante autorização; veja [persistência](../persistence.md).
  PostgreSQL não foi reexecutado nesta etapa; a persistência permanece coberta
  pela entrega anterior e pela CI. A UI real do frontend e HTTPS em produção
  ainda precisam de validação na integração correspondente.
