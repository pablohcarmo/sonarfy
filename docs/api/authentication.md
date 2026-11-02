# Contrato de login e emissão de token de acesso

Primeira entrega da [issue #22](https://github.com/pablohcarmo/sonarfy/issues/22).
Base local dos exemplos: `http://localhost:8081`; ajuste para seu ambiente.
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

Exemplo com curl (shell Bash):

```bash
curl -i -X POST 'http://localhost:8081/api/auth/login' \
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

## Limite desta entrega

O endpoint emite a credencial, mas a aplicação ainda não implementa o filtro
Bearer que valida esse token e estabelece o `Principal` das requisições.
Assim, obter um token ainda não permite executar `/api/users/**` de ponta a ponta.
O consumo da credencial nas rotas protegidas será implementado no próximo PR.
A issue #22 permanece aberta; a integração Spotify aguarda sua conclusão.

Os testes de login integram controller, serviço, BCrypt e JWT com repositório
simulado; não comprovam acesso autenticado pela cadeia de segurança HTTP.
Veja também o [contrato de recuperação e troca de senha](passwords.md).
