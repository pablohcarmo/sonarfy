# Contrato de conta e perfil do usuário

Base local dos exemplos: `http://localhost:8080`; ajuste para o seu ambiente.
Este documento descreve os endpoints autenticados de consulta e atualização cadastral (`UserController`) e de personalização de perfil (`ProfileController`):

1. `GET /api/users/me`
2. `PUT /api/users/me`
3. `GET /api/profile/me`
4. `PUT /api/profile/me`

As rotas de troca autenticada de senha (`POST /api/users/me/password-change-request` e `PUT /api/users/me/password`), também declaradas em `UserController`, estão documentadas no [contrato de recuperação e troca de senha](passwords.md).

---

## Autenticação HTTP obrigatória

Todas as rotas sob `/api/users/**` e `/api/profile/**` exigem autenticação via cabeçalho HTTP:

```http
Authorization: Bearer <accessToken>
```

- O `<accessToken>` é obtido em `POST /api/auth/login` e possui `purpose: "access"` com validade de 15 minutos (`900` segundos).
- O filtro `BearerAuthenticationFilter` valida assinatura, emissor (`sonarfy-api`), expiração, propósito `access` e confirma no banco que o usuário continua existente, ativo (`active = true`) e verificado (`verified = true`).
- A identidade autenticada é exposta no `Principal`, cujo `getName()` retorna o `email` do usuário logado.
- Requisições sem cabeçalho `Authorization`, com token inválido/expirado ou com tokens de outros propósitos (`pending_registration`, `email_confirmation`, `password_reset`, `password_change`) recebem HTTP `401 Unauthorized`. Consulte o [contrato de autenticação](authentication.md).

---

## Representação de resposta comum (`UserDto`)

Os quatro endpoints retornam HTTP `200 OK` com `Content-Type: application/json` serializando `UserDto`:

```json
{
  "id": 1,
  "name": "Maria",
  "surname": "Silva",
  "handle": "maria.silva",
  "email": "maria.silva@example.com",
  "city": "São Paulo",
  "country": "Brasil",
  "profilePicture": "https://cdn.example.com/avatars/maria.png",
  "wallpaper": "https://cdn.example.com/wallpapers/capa.jpg",
  "biography": "Apaixonada por rock progressivo e MPB.",
  "birthDate": "2000-05-15",
  "creationDate": "2026-11-01T14:30:00",
  "lastUpdateDate": "2026-11-10T10:15:20",
  "active": true
}
```

### Observações sobre os campos serializados em `UserDto`

- **`profilePicture` vs `avatar`:** Na entidade `User` e no DTO de entrada `UpdateProfileDto`, o campo chama-se `avatar`; porém, no `UserDto` de resposta, o getter `getProfilePicture()` serializa a propriedade JSON como `"profilePicture"`.
- **`active`:** O campo booleano interno `isActive` é exposto pelo getter `isActive()`, sendo serializado pelo Jackson como `"active"`.
- **`displayName`:** O nome de exibição (`users.display_name`) é gerado internamente no cadastro, mas não faz parte dos campos atuais de `UserDto`.

---

## 1. Consultar dados do usuário logado

- `GET /api/users/me` (`UserController.getCurrentUser`)
- `GET /api/profile/me` (`ProfileController.getCurrentUser`)

Ambos os endpoints não recebem corpo, identificam o usuário autenticado por `Principal.getName()` (e-mail) e delegam para `UserService.getUserRegister(email)`, retornando `200 OK` com o mesmo `UserDto`.

Exemplo com `curl`:

```bash
curl -i 'http://localhost:8080/api/users/me' \
  -H 'Authorization: Bearer SUBSTITUA_PELO_TOKEN_DE_ACESSO'
```

---

## 2. Atualizar dados cadastrais

`PUT /api/users/me` — exige `Authorization: Bearer <accessToken>` e `Content-Type: application/json`.

O corpo da requisição é representado por `UpdateUserDto`:

```json
{
  "name": "Maria Clara",
  "surname": "Silva",
  "handle": "maria.silva",
  "city": "Campinas",
  "country": "Brasil",
  "birthDate": "2000-05-15"
}
```

### Comportamento atual da implementação

1. **Campos efetivamente atualizados:** `UserService.updateRegister` atualiza `name`, `surname`, `birthDate`, `city` e `country` na entidade `User` e retorna o `UserDto` atualizado (`200 OK`).
2. **Campo `handle` ignorado na atualização:** Embora `UpdateUserDto` possua a propriedade `handle`, `UserService.updateRegister` não altera o `handle` da conta (preservando o identificador original).
3. **Validações em `UpdateUserDto`:** O controller declara `@Valid`, mas a classe `UpdateUserDto` atualmente não possui anotações de Bean Validation (`@NotBlank`, `@Pattern`, `@Past`). As restrições de nulidade e tamanho continuam impostas pelas colunas da entidade `User` e pelo schema do banco de dados.

---

## 3. Atualizar perfil público (bio, avatar e capa)

`PUT /api/profile/me` — exige `Authorization: Bearer <accessToken>` e `Content-Type: application/json`.

O corpo da requisição é representado por `UpdateProfileDto`:

```json
{
  "biography": "Colecionadora de vinis e fã de música brasileira.",
  "avatar": "https://cdn.example.com/avatars/maria-novo.png",
  "wallpaper": "https://cdn.example.com/wallpapers/vitrola.jpg"
}
```

### Validações e limites

- `biography` possui a anotação `@Size(max = 255, message = "Biography must be at most 255 characters")` no `UpdateProfileDto`.
  - **Atenção ao limite do banco de dados:** Na entidade `User` (`@Column(length = 150)`) e no schema SQL (`biography VARCHAR(150)` em `db/tables.sql`), a coluna possui limite de **150 caracteres**. Recomenda-se que clientes limitem `biography` a no máximo 150 caracteres para evitar erro de persistência no banco.
- Os valores enviados em `avatar`, `wallpaper` e `biography` substituem os valores atuais do usuário e são retornados no `UserDto` (`200 OK`), onde `avatar` aparece na propriedade `"profilePicture"`.

---

## Resumo de respostas HTTP (`/api/users/me` e `/api/profile/me`)

| Situação | HTTP | Comportamento |
| --- | --- | --- |
| Consulta ou atualização realizada com sucesso | `200 OK` | Retorna `UserDto` em JSON |
| `biography` acima de 255 caracteres em `PUT /api/profile/me` ou JSON malformado | `400 Bad Request` | Rejeitado na validação de entrada |
| Token Bearer ausente, inválido, expirado, de outro propósito ou usuário inativo/não verificado | `401 Unauthorized` | Rejeitado por `BearerAuthenticationFilter` / `SecurityConfig` |

---

## Cobertura de testes automatizados

| Classe de teste | O que valida neste contrato |
| --- | --- |
| `UserControllerTest` | Consulta e atualização de cadastro em `/api/users/me` |
| `UserServiceTest` | Regras de `getUserRegister`, `updateRegister` e `updateProfile` |
| `SecurityChainIntegrationTest` | Acesso autenticado a `GET /api/users/me` após `POST /api/auth/login` e rejeição (`401`) para requisições anônimas, tokens inválidos ou contas não elegíveis |
