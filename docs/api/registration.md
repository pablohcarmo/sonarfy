# Contrato de cadastro, retificação e ativação de conta

Base local dos exemplos: `http://localhost:8080`; ajuste para o seu ambiente.
Este documento descreve os quatro endpoints públicos do ciclo inicial de criação e confirmação de conta em `AuthController`:

1. `POST /api/auth/register`
2. `PATCH /api/auth/update-pending-email`
3. `POST /api/auth/resend-activation-email`
4. `GET /api/auth/verify`

Para login e emissão de token de acesso (`purpose: "access"`), consulte o [contrato de autenticação](authentication.md). Para recuperação e troca de senha, consulte o [contrato de senhas](passwords.md).

---

## 1. Cadastrar novo usuário

`POST /api/auth/register` — público (`permitAll`), `Content-Type: application/json`.

O corpo da requisição é representado por `NewUserDto`:

```json
{
  "name": "Maria",
  "surname": "Silva",
  "handle": "maria.silva",
  "email": "maria.silva@example.com",
  "password": "SenhaDeTeste123!",
  "city": "São Paulo",
  "country": "Brasil",
  "birthDate": "2000-05-15"
}
```

### Validações de entrada e regras de negócio

| Campo | Validação Bean Validation (`@Valid`) | Regras adicionais no serviço (`UserService.newUser`) |
| --- | --- | --- |
| `name` | `@NotBlank` e `@Pattern` (apenas letras, espaços Simples, apóstrofos e hífens entre palavras) | Usado junto com `surname` (com `strip()`) para compor `displayName` limitado a 60 pontos de código Unicode |
| `surname` | `@NotBlank` e `@Pattern` (apenas letras, espaços simples, apóstrofos e hífens entre palavras) | Compõe `displayName` persistido em `users.display_name` |
| `handle` | `@NotBlank` e `@Pattern("^[a-zA-Z0-9_.-]+$")` | Se enviado com `@` inicial, `cleanHandle` remove o prefixo; unicidade verificada sem distinção de maiúsculas/minúsculas |
| `email` | `@NotBlank` e `@Email` | Unicidade verificada sem distinção de maiúsculas/minúsculas (`findByEmailIgnoreCaseOrHandleIgnoreCase` e índice `uk_users_email_ci`) |
| `password` | `@NotBlank` e `@Size(min = 8)` | Rejeita senhas com mais de **72 bytes em UTF-8** (`400 Bad Request`) antes do hash BCrypt |
| `city` | `@NotBlank` e `@Pattern("^[a-zA-ZÀ-ÿ\\s.'-]+$")` | Persistido no cadastro do usuário |
| `country` | `@NotBlank` e `@Pattern("^[a-zA-ZÀ-ÿ\\s.'-]+$")` | Persistido no cadastro do usuário |
| `birthDate` | `@NotNull` e `@Past` | Idade mínima de **13 anos completos** na data atual (`400 Bad Request` caso menor de 13 anos) |

No momento da persistência:
- A conta recebe a permissão padrão `ROLE_USER`.
- `User.onCreate()` inicializa `active = true` e `verified = false` (o login em `POST /api/auth/login` permanece bloqueado até a confirmação do e-mail).
- A transação publica o evento `ActivationEmailRequested(userId)`. O envio do e-mail de ativação ocorre **após o commit** (`ActivationEmailListener` com `TransactionPhase.AFTER_COMMIT` e `Propagation.NOT_SUPPORTED`), garantindo que uma falha SMTP não desfaça o cadastro já persistido.

### Resposta de sucesso

HTTP `201 Created`, `Content-Type: application/json`, representado por `RegisterResponseDto`:

```json
{
  "message": "User registered successfully! Please wait for activation instructions.",
  "email": "maria.silva@example.com",
  "registrationToken": "SUBSTITUA_PELO_REGISTRATION_TOKEN_RETORNADO",
  "expiresInSeconds": 900
}
```

- `registrationToken` é um JWT assinado com HMAC256 (`iss: "sonarfy-api"`, `sub: "<userId>"`, `purpose: "pending_registration"`, validade de 15 minutos / `900` segundos).
- Esse token serve **exclusivamente** para autorizar a correção do e-mail pendente em `PATCH /api/auth/update-pending-email` caso o usuário tenha digitado o endereço incorretamente no cadastro. Ele **não** autentica rotas protegidas (`/api/users/**`, `/api/profile/**`, `/api/spotify/**`).

### Respostas de erro previstas

| Situação | HTTP | Comportamento |
| --- | --- | --- |
| Campos ausentes, formato inválido, senha < 8 caracteres ou > 72 bytes UTF-8, data futura ou idade < 13 anos | `400 Bad Request` | Cadastro rejeitado antes de persistir |
| `email` ou `handle` já utilizado por outra conta (comparação case-insensitive) | `409 Conflict` | `"User with this email or handle already exists!"` |

---

## 2. Retificar e-mail pendente antes da ativação

`PATCH /api/auth/update-pending-email` — rota pública na cadeia HTTP, protegida no controller pelo token `pending_registration` enviado no cabeçalho `Authorization: Bearer <registrationToken>`.

### Requisição

Cabeçalhos:
- `Authorization: Bearer <registrationToken>`
- `Content-Type: application/json`

Corpo representado por `UpdatePendingEmailDto`:

```json
{
  "newEmail": "maria.correto@example.com"
}
```

`newEmail` é validado com `@NotBlank` e `@Email`.

### Comportamento e respostas

Ao atualizar o endereço de uma conta ainda não verificada (`verified = false`), o serviço grava o novo `email` e publica um novo evento `ActivationEmailRequested(userId)` para envio pós-commit (sujeito ao cooldown compartilhado de 30 segundos por conta).

| Situação | HTTP | Resposta / Motivo |
| --- | --- | --- |
| E-mail pendente atualizado com sucesso | `200 OK` | `"Pending email updated successfully!"` |
| Cabeçalho `Authorization` ausente, sem prefixo `Bearer `, token inválido/expirado, `purpose` diferente de `pending_registration` ou `sub` não numérico | `401 Unauthorized` | Rejeitado no `AuthController` antes de chamar o serviço |
| `newEmail` vazio/malformado ou conta já verificada (`verified = true`) | `400 Bad Request` | `"User already verified. You can log in."` (quando já ativada) |
| Usuário referenciado no token não encontrado | `404 Not Found` | `"User not found!"` |
| `newEmail` já pertence a outra conta | `409 Conflict` | `"E-mail is already in use by another account."` |

---

## 3. Reenviar e-mail de ativação

`POST /api/auth/resend-activation-email` — público (`permitAll`).

### Formato de entrada

Diferentemente de `POST /api/auth/forgot-password` (que recebe JSON via `@RequestBody`), este endpoint recebe o parâmetro `email` via `@RequestParam("email")` (query string ou `application/x-www-form-urlencoded`):

```bash
curl -i -X POST 'http://localhost:8080/api/auth/resend-activation-email?email=maria.silva@example.com'
```

### Comportamento e respostas atuais

| Situação | HTTP | Comportamento atual |
| --- | --- | --- |
| Conta existente, não verificada e fora da janela de cooldown | `200 OK` | Reserva o cooldown de 30s (`EmailCooldownService.reserveOrThrow`), envia o novo link e retorna `"Confirmation email request processed. Please check your inbox."` |
| Parâmetro `email` em branco | `200 OK` | `"Invalid e-mail provided for resending confirmation."` |
| Conta já verificada (`verified = true`) | `200 OK` | `"User already verified. You can log in."` |
| Nova tentativa dentro do intervalo de 30 segundos (`app.email.cooldown-seconds`) | `429 Too Many Requests` | `"Please wait 30 seconds before requesting another email."` |
| E-mail não encontrado no banco | Erro (`UsernameNotFoundException`) | A uniformização desta resposta pública está rastreada na [Issue #43](https://github.com/pablohcarmo/sonarfy/issues/43) |

---

## 4. Confirmar e-mail e ativar conta

`GET /api/auth/verify` — público (`permitAll`).

### Requisição

Recebe na query string o token JWT enviado por e-mail (`purpose: "email_confirmation"`, validade de 15 minutos):

```bash
curl -i 'http://localhost:8080/api/auth/verify?token=SUBSTITUA_PELO_TOKEN_DE_CONFIRMACAO'
```

### Comportamento e respostas

O controller avalia a mensagem retornada por `UserService.verifyToken(token)`: se contiver `"success"` ou `"already verified"`, responde `200 OK`; caso contrário, responde `400 Bad Request`.

| Situação | HTTP | Corpo da resposta (`text/plain`) |
| --- | --- | --- |
| Token válido e conta ativada pela primeira vez (`verified` passa para `true`) | `200 OK` | `"E-mail verified successfully! You can now log in."` |
| Token válido, mas a conta já estava verificada (idempotência) | `200 OK` | `"User already verified. You can log in."` |
| Parâmetro `token` vazio ou em branco | `400 Bad Request` | `"Invalid token provided for verification."` |
| Token expirado, assinatura inválida ou `purpose` diferente de `email_confirmation` | `400 Bad Request` | `"Invalid or expired token. Please, request a new confirmation email."` |
| `sub` do token não é um número `long` válido | `400 Bad Request` | `"Invalid token format. Please, request a new confirmation email."` |
| Usuário do token não encontrado no banco | `400 Bad Request` | `"User not found or account was deleted."` |

Quando a conta transita para `verified = true`, o serviço dispara o e-mail de boas-vindas (`AuthEmailService.sendWelcomeEmail`), capturando eventuais exceções de transporte para não reverter a ativação.

---

## Cobertura de testes automatizados

| Classe de teste | O que valida neste contrato |
| --- | --- |
| `AuthControllerTest` | Contrato HTTP de cadastro (`201`), validações de entrada (`400`), retificação de e-mail pendente com `Bearer` (`200`/`401`), reenvio e verificação (`200`/`400`) |
| `UserServiceTest` | Regras de idade mínima (13 anos), limite de 72 bytes UTF-8 na senha, geração de `displayName`, limpeza de `@` no handle, conflitos (`409`) e transição de `verified` |
| `ActivationEmailListenerTest` | Processamento do evento `ActivationEmailRequested` após o commit da transação |
| `EmailReservationIntegrationTest` | Reserva atômica do cooldown de 30s no banco, ausência de envio em caso de rollback do cadastro e fluxo completo de cadastro -> ativação -> login |
| `SecurityChainIntegrationTest` | Liberação pública das rotas de cadastro/ativação e rejeição do `registrationToken` (`pending_registration`) ao tentar acessar rotas protegidas (`/api/users/**`) |
