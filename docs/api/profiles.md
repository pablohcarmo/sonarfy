# Leitura de perfil

GET `/api/profile/me` e GET `/api/profile/by-handle/{handle}` exigem Bearer access
de uma conta existente, ativa e verificada. A consulta por handle ignora caixa,
retorna o handle canônico e não aceita `@` como prefixo especial. O segmento
`by-handle` permite consultar inclusive um usuário cujo handle seja `me`.

## Resposta 200 — ProfileDto

```json
{
  "identity": {
    "id": 1,
    "handle": "maria",
    "displayName": "Maria Música",
    "avatarUrl": null,
    "coverUrl": null,
    "biography": null,
    "location": { "city": "Campinas", "country": "Brasil" },
    "joinedAt": "2026-11-01T14:30:00Z"
  },
  "viewer": { "isSelf": true, "canEdit": true }
}
```

Identidade contém exclusivamente ID, handle, nome de exibição persistido (até 60
caracteres, sem truncar valores existentes), URLs de mídia, bio, cidade/país e
data de entrada ISO 8601 com offset. Mídia e bio podem ser nulas. A leitura não
valida novamente URLs legadas nem realiza upload ou chamadas a provedores.

`viewer` é calculado pelo ID autenticado: consultar o próprio handle equivale a
`/me`; para terceiros ambos os indicadores são false. `canEdit` expressa propriedade,
sem ampliar os campos editáveis pelo PUT atual. Estatísticas, relacionamento,
listas e capacidades sociais não integram a resposta.

Nome/sobrenome cadastral, e-mail, nascimento, permissões, credenciais e estado
Spotify não são retornados. Cidade/país e data de entrada são visíveis aos usuários
autenticados nesta fase. Não há configuração de perfil privado nem acesso anônimo.

## Erros e cache

- 401: Bearer ausente, inválido, expirado, com outro propósito ou conta autenticada
  inexistente, inativa ou não verificada.
- 404: handle sem correspondência ou perfil consultado inativo/não verificado.
  Não distinguir esses casos pelo contrato de erro. Handles fora do formato
  cadastral também não encontram perfil; não há validação de cadastro neste GET.
- Respostas de sucesso incluem `Cache-Control: no-store`, pois dependem do visitante.

## Compatibilidade e consultas

Esta entrega altera intencionalmente GET `/api/profile/me`: antes retornava
`UserDto`; agora retorna `ProfileDto` composto. Dados cadastrais continuam em
GET `/api/users/me`. PUT `/api/profile/me` permanece com entrada e resposta legadas,
incluindo os cuidados de privacidade de `UserDto`: não usar seu retorno como perfil
de terceiros. A edição do nome de exibição será uma entrega posterior.

Uma projeção seleciona apenas os campos de identidade em uma consulta ao usuário
alvo, além da consulta de autenticação existente. Não carrega coleções ou associações
por item. Reutiliza o schema existente; sem migration. O aproveitamento do índice
`uk_users_handle_ci` deverá ser medido no PostgreSQL antes de otimizações adicionais.

Coleção importável: [sonarfy-profiles.postman_collection.json](sonarfy-profiles.postman_collection.json).
Integração: [profile-page.md](../integrations/profile-page.md).

## Validação local — 19/11/2026

- Suíte imediatamente após criar `feat/profile-query`: 654 testes reportados,
  zero falhas/erros e 65 ignorados.
- `ProfileIntegrationTest`: oito cenários aprovados sobre H2 descartável, com
  MockMvc e cadeia real de segurança, serviços e JPA. Cobrem propriedade, campos
  permitidos, nulos, nome persistido com 60 caracteres, data com offset, conta
  cadastral preservada, alvos indisponíveis e rejeição de credenciais.
- Suíte completa final (`.\mvnw.cmd test`): 662 testes reportados, zero falhas/erros
  e 65 ignorados. Inclui rejeição de token expirado nos testes de perfil.
- JSON e sintaxe dos scripts da coleção de cinco requisições verificados.
  Não houve execução automatizada da coleção contra uma aplicação via rede.
- Validação manual no Postman realizada pelo usuário contra a aplicação local:
  GET `/api/users/me` preservado; GET `/api/profile/me` e consulta de outro perfil
  por handle retornaram 200, com identidade sem dados cadastrais privados,
  `viewer` correspondente à propriedade e `Cache-Control: no-store`.
  Consulta autenticada de handle sem correspondência retornou 404; ambas as
  rotas de perfil sem Authorization retornaram 401. Evidência fornecida pelo
  usuário em respostas JSON, capturas e confirmação dos status; não representa
  execução automatizada das asserções da coleção nem validação PostgreSQL específica.
- O teste opt-in PostgreSQL de troca de e-mail foi adaptado ao novo DTO, mas não
  executado nesta entrega. Sem validação PostgreSQL, plano EXPLAIN, navegador,
  integração frontend ou CI remoto nesta execução. Nenhuma migration aplicada.
