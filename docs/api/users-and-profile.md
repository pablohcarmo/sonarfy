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
- Os controllers usam o ID imutável do usuário autenticado. O e-mail retornado por `Principal.getName()` não é usado para selecionar a conta, pois pode mudar.
- Requisições sem cabeçalho `Authorization`, com token inválido/expirado ou com tokens de outros propósitos (`pending_registration`, `email_confirmation`, `password_reset`, `password_change`, `email_change`) recebem HTTP `401 Unauthorized`. Consulte o [contrato de autenticação](authentication.md).

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
- **`displayName`:** O nome de exibição do perfil (`users.display_name`) é composto inicialmente no cadastro. Depois disso, sua edição pertence ao perfil; alterar nome/sobrenome nas configurações preserva o valor existente. Não faz parte de `UserDto` nem pode ser informado neste PUT.

---

## 1. Consultar dados do usuário logado

- `GET /api/users/me` (`UserController.getCurrentUser`)
- `GET /api/profile/me` (`ProfileController.getCurrentUser`)

Ambos os endpoints não recebem corpo, identificam o usuário pelo ID autenticado e
delegam para `UserService.getUserRegister(userId)`, retornando `200 OK` com o mesmo
`UserDto`. Atualizações de dados e perfil bloqueiam/recarregam esse usuário antes
de salvar, preservando alterações concorrentes de e-mail e senha.

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

### Substituição dos dados pessoais

Os seis campos são obrigatórios: omissão, `null` ou texto em branco recebem `400`.
O PUT substitui esses seis valores; clientes devem carregar o GET e enviar também
os valores não alterados. `UpdateUserDto` e `UserDto` são records; o JSON de resposta
permanece igual, inclusive `active` e `profilePicture`.

| Campo | Regras compartilhadas com o cadastro |
| --- | --- |
| `name`, `surname` | Letras latinas, espaços, apóstrofos e hífens conforme padrão do cadastro; até 50 caracteres cada |
| `handle` | Letras ASCII, números, `_`, `-`, `.`; entre 1 e 15 caracteres, sem prefixo `@` ou espaços |
| `city`, `country` | Padrão de localização do cadastro: letras, espaços, apóstrofos, hífens e pontos; até 100 caracteres cada |
| `birthDate` | Data ISO `YYYY-MM-DD` no passado, com pelo menos 13 anos completos na data atual do servidor |

Cadastro e edição convertem letras para minúsculas usando `Locale.ROOT` antes de
consultar a unicidade e persistir. A resposta do PUT e consultas posteriores retornam
essa representação: `Maria.Silva-9` resulta em `maria.silva-9`. Os caracteres
permitidos e o limite de 15 permanecem iguais aos do cadastro; espaços, prefixo
`@`, acentos, barras e outros símbolos são rejeitados com `400`, sem correção silenciosa.
Handles antigos não são regravados em lote; passam à forma minúscula quando editados.

É permitido manter o próprio handle ou enviar apenas uma variação de caixa. Um handle ocupado
por outra conta recebe `409`, sem distinção entre maiúsculas e minúsculas.
A transação e o índice PostgreSQL `uk_users_handle_ci` protegem também contra
colisões concorrentes; somente a violação desse índice com SQLSTATE `23505` é
traduzida em conflito de handle. Outros erros de persistência não viram `409`.
Uma atualização rejeitada não persiste parcialmente.

A identidade vem exclusivamente do Bearer. Propriedades adicionais não são
aplicadas: `id`, e-mail, senha, permissão, estado da conta, `displayName`, avatar,
capa e biografia não podem ser alterados por esse DTO. Avatar, capa e biografia
continuam opcionais e pertencem ao fluxo de perfil.

`displayName` é gerado apenas no cadastro, a partir de nome e sobrenome com
`strip()`, limitado a 60 pontos de código Unicode. O PUT preserva esse campo,
inclusive quando ele tiver sido personalizado no perfil.
Não há revogação de sessões nem exigência de novo login nesta operação. O novo
handle passa a valer também para login; o antigo deixa de identificar esta conta.

Coleção importável: [sonarfy-users.postman_collection.json](sonarfy-users.postman_collection.json).
Orientação para a tela: [configurações — dados pessoais](../integrations/user-settings.md).

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
| Dado pessoal omitido, nulo, em branco, fora do formato/limite ou idade inferior a 13 anos | `400 Bad Request` | Nenhum dado pessoal é alterado |
| Handle ocupado, inclusive por corrida concorrente ou variação de caixa | `409 Conflict` | Nenhum dado pessoal é alterado |
| `biography` acima de 255 caracteres em `PUT /api/profile/me` ou JSON malformado | `400 Bad Request` | Rejeitado na validação de entrada |
| Token Bearer ausente, inválido, expirado, de outro propósito ou usuário inativo/não verificado | `401 Unauthorized` | Rejeitado por `BearerAuthenticationFilter` / `SecurityConfig` |

---

## Cobertura de testes automatizados

| Classe de teste | O que valida neste contrato |
| --- | --- |
| `UserControllerTest` | Consulta e atualização de cadastro em `/api/users/me` |
| `UserServiceTest` | Regras de cadastro, senha, retificação pendente e classificação de erros de persistência |
| `SecurityChainIntegrationTest` | Acesso autenticado a `GET /api/users/me` após `POST /api/auth/login` e rejeição (`401`) para requisições anônimas, tokens inválidos ou contas não elegíveis |
| `PersonalDataIntegrationTest` | GET/PUT pela cadeia real de segurança, persistência, limites, idade, isolamento, campos protegidos e rejeição de credenciais; corrida concorrente opt-in em PostgreSQL descartável |
| `EmailChangeConfirmationPostgresTest` | Conta/perfil por ID após reutilização do e-mail antigo; edição concorrente de perfil preservando a troca confirmada |
