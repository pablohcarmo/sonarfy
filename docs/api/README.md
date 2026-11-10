# Documentação da API — Sonarfy

Este diretório concentra os contratos HTTP versionados da API do Sonarfy (Java 21, Spring Boot `4.1.0`, Spring Security, Spring Data JPA e `com.auth0:java-jwt:4.4.0`).

---

## Índice de Contratos por Domínio

1. **[Cadastro, Retificação e Ativação de Conta](registration.md)**
   - `POST /api/auth/register` — Criação de conta (`verified = false`), geração do `registrationToken` e disparo pós-commit do e-mail de ativação.
   - `PATCH /api/auth/update-pending-email` — Correção do endereço de e-mail pendente mediante `Authorization: Bearer <registrationToken>`.
   - `POST /api/auth/resend-activation-email` — Reenvio do link de confirmação respeitando o cooldown atômico de 30 segundos.
   - `GET /api/auth/verify` — Confirmação do e-mail (`verified = true`) e disparo do e-mail de boas-vindas.
2. **[Login e Autenticação HTTP Bearer](authentication.md)**
   - `POST /api/auth/login` — Autenticação por e-mail ou handle (com ou sem `@`) e emissão do `accessToken` (`purpose: "access"`).
   - Regras do `BearerAuthenticationFilter`, política de rotas públicas do `SecurityConfig` e configuração de CORS (`CorsConfig`).
3. **[Recuperação e Troca de Senha](passwords.md)**
   - `POST /api/auth/forgot-password` — Solicitação pública de recuperação com resposta uniforme contra enumeração.
   - `POST /api/auth/reset-password` — Redefinição pública de senha esquecida com consumo atômico de token (`purpose: "password_reset"`).
   - `POST /api/users/me/password-change-request` — Solicitação autenticada de token para alteração de senha (`purpose: "password_change"`).
   - `PUT /api/users/me/password` — Troca autenticada exigindo a senha atual e o token de autorização no corpo JSON.
   - Coleção Postman versionada: [sonarfy-passwords.postman_collection.json](sonarfy-passwords.postman_collection.json).
4. **[Conta e Perfil do Usuário](users-and-profile.md)**
   - `GET /api/users/me` e `PUT /api/users/me` — Consulta e atualização de dados cadastrais (`UserDto` / `UpdateUserDto`).
   - `GET /api/profile/me` e `PUT /api/profile/me` — Consulta e personalização de biografia, avatar (`profilePicture`) e capa (`UpdateProfileDto`).
5. **[Integração OAuth 2.0 com o Spotify](../integrations/spotify.md)**
   - `POST /api/spotify/authorization` — Geração autenticada da URL de autorização do Spotify e registro de `state` de uso único.
   - `GET /api/spotify/callback` — Processamento público do retorno OAuth, persistência cifrada (`AES-256-GCM`) e redirecionamento `302 Found` ao frontend (`SPOTIFY_FRONTEND_REDIRECT_URI?spotify=connected|denied|invalid_state|error`).
   - `GET /api/spotify/connection` — Consulta autenticada do estado da conexão (`CONNECTED` / `DISCONNECTED` e `connectedAt`).
   - `DELETE /api/spotify/connection` — Desconexão idempotente com remoção das credenciais locais e invalidação de autorizações em trânsito.

---

## Mapa Consolidado de Endpoints (16 Rotas Ativas)

| Método | Rota | Controller | Acesso | Contrato Detalhado |
| --- | --- | --- | --- | --- |
| `POST` | `/api/auth/register` | `AuthController` | Público | [registration.md](registration.md#1-cadastrar-novo-usuário) |
| `PATCH` | `/api/auth/update-pending-email` | `AuthController` | Público + Bearer `pending_registration` | [registration.md](registration.md#2-retificar-e-mail-pendente-antes-da-ativação) |
| `POST` | `/api/auth/resend-activation-email` | `AuthController` | Público | [registration.md](registration.md#3-reenviar-e-mail-de-ativação) |
| `GET` | `/api/auth/verify` | `AuthController` | Público + query `token` (`email_confirmation`) | [registration.md](registration.md#4-confirmar-e-mail-e-ativar-conta) |
| `POST` | `/api/auth/login` | `AuthController` | Público | [authentication.md](authentication.md#login) |
| `POST` | `/api/auth/forgot-password` | `AuthController` | Público | [passwords.md](passwords.md#solicitar-recuperação) |
| `POST` | `/api/auth/reset-password` | `AuthController` | Público + JSON `token` (`password_reset`) | [passwords.md](passwords.md#redefinir-senha-esquecida) |
| `GET` | `/api/users/me` | `UserController` | Bearer `access` | [users-and-profile.md](users-and-profile.md#1-consultar-dados-do-usuário-logado) |
| `PUT` | `/api/users/me` | `UserController` | Bearer `access` | [users-and-profile.md](users-and-profile.md#2-atualizar-dados-cadastrais) |
| `POST` | `/api/users/me/password-change-request` | `UserController` | Bearer `access` | [passwords.md](passwords.md#solicitar-token-para-troca-autenticada) |
| `PUT` | `/api/users/me/password` | `UserController` | Bearer `access` + JSON `token` (`password_change`) | [passwords.md](passwords.md#trocar-senha-estando-autenticado) |
| `GET` | `/api/profile/me` | `ProfileController` | Bearer `access` | [users-and-profile.md](users-and-profile.md#1-consultar-dados-do-usuário-logado) |
| `PUT` | `/api/profile/me` | `ProfileController` | Bearer `access` | [users-and-profile.md](users-and-profile.md#3-atualizar-perfil-público-bio-avatar-e-capa) |
| `POST` | `/api/spotify/authorization` | `SpotifyController` | Bearer `access` | [spotify.md](../integrations/spotify.md) |
| `GET` | `/api/spotify/callback` | `SpotifyController` | Público + `state` OAuth | [spotify.md](../integrations/spotify.md) |
| `GET` | `/api/spotify/connection` | `SpotifyController` | Bearer `access` | [spotify.md](../integrations/spotify.md) |
| `DELETE` | `/api/spotify/connection` | `SpotifyController` | Bearer `access` | [spotify.md](../integrations/spotify.md) |

---

## Isolamento de Finalidades dos Tokens JWT (`JwtService`)

Todos os tokens JWT emitidos pelo Sonarfy utilizam o emissor `iss: "sonarfy-api"`, `sub: "<userId>"` e validade de **15 minutos (`900` segundos)**, mas possuem finalidades estritamente isoladas pela claim `purpose`:

| Claim `purpose` | Emitido em | Onde é aceito | Aceito como login nas rotas protegidas? |
| --- | --- | --- | --- |
| `access` | `POST /api/auth/login` | Cabeçalho `Authorization: Bearer` em `/api/users/**`, `/api/profile/**` e `/api/spotify/**` | **Sim** (desde que o usuário continue ativo e verificado) |
| `pending_registration` | `POST /api/auth/register` | Cabeçalho `Authorization: Bearer` apenas em `PATCH /api/auth/update-pending-email` | **Não** |
| `email_confirmation` | E-mail de cadastro / reenvio | Query string `?token=` apenas em `GET /api/auth/verify` | **Não** |
| `password_reset` | E-mail de `POST /api/auth/forgot-password` | Campo JSON `"token"` apenas em `POST /api/auth/reset-password` | **Não** |
| `password_change` | E-mail de `POST /api/users/me/password-change-request` | Campo JSON `"token"` apenas em `PUT /api/users/me/password` (junto com Bearer `access`) | **Não** |
