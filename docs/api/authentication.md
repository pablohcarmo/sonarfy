# Contrato de login e emissão de token de acesso

Contrato de login e acesso Bearer da [issue #22](https://github.com/pablohcarmo/sonarfy/issues/22).
Base local dos exemplos: `http://localhost:8080`; ajuste para seu ambiente.
Use uma conta de teste cadastrada, ativa e com o e-mail confirmado.

## Login

`POST /api/auth/login` — público, `Content-Type: application/json`.

O corpo é representado por `LoginDto`:

```json
{
  "login": "conta-teste@example.com",
  "password": "SenhaDeTeste123!"
}
```

O campo `login` aceita e-mail ou handle, incluindo handle com `@` inicial.
Por exemplo, `"login": "pablo"` e `"login": "@pablo"` consultam o handle `pablo`.
A busca ignora maiúsculas e minúsculas, e espaços nas extremidades do login
são removidos. A senha é comparada com BCrypt, sem remover espaços ou alterar
maiúsculas e minúsculas.

Ambos os campos são obrigatórios e não podem ser vazios ou conter somente espaços.
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
  -d '{"login":"conta-teste@example.com","password":"SenhaDeTeste123!"}'
```

## Resposta de sucesso

HTTP `200 OK`, JSON representado por `LoginResponseDto`:

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
Não há revogação automática por troca de senha nesta entrega; o token continua
sujeito à expiração e à situação atual da conta.

`/api/auth/update-pending-email` continua validando seu Bearer de cadastro no
controller; esse token não autentica acesso a `/api/users/**`.

## Rotas públicas e CORS

O `SecurityConfig` permite acesso sem login somente aos pares de método e caminho:

| Método | Caminho |
| --- | --- |
| POST | `/api/auth/register` |
| POST | `/api/auth/login` |
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
origens permitidas por `*`, métodos GET, POST, PUT, PATCH, DELETE e OPTIONS,
cabeçalhos permitidos por `*` e credenciais de navegador desabilitadas.
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
