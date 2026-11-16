# Troca de e-mail de conta verificada

Este contrato cobre solicitação e confirmação. O fluxo permanece **desativado por
padrão**. A ativação para usuários exige a migration 006, configuração de SMTP e
a futura página de confirmação do frontend. A migration no banco da aplicação
depende de autorização; não há execução automática.
Nos testes isolados, `app.email-change.enabled=true` habilita o endpoint e o remetente.
Com a opção desativada, não há mapeamento do endpoint nem agendamento de envios.

## Solicitar troca

`POST /api/users/me/email-change-request`, com Bearer `access` de conta ativa e
verificada e `Content-Type: application/json`:

```json
{
  "newEmail": "novo@example.com",
  "currentPassword": "SUBSTITUA_LOCALMENTE"
}
```

Os dois campos são obrigatórios e não podem ser vazios. O endereço deve ter
formato válido e no máximo 150 caracteres; é normalizado para minúsculas.
Espaços externos não são removidos: entradas inválidas são rejeitadas.
A senha atual é novamente validada após bloquear e recarregar a conta.

Resposta `202 Accepted`:

```json
{
  "message": "Email change request accepted for delivery.",
  "expiresInSeconds": 900
}
```

`202` significa que a solicitação e a intenção de envio foram persistidas
atomicamente. Não confirma entrega SMTP nem troca efetiva do endereço. O e-mail
atual, a senha, o estado da conta e as sessões permanecem intactos nesta operação.
O JSON não contém token ou link; somente o endereço novo recebe a confirmação.

| Situação | HTTP |
| --- | --- |
| Solicitação aceita para envio | `202` |
| JSON/campos inválidos, endereço acima do limite ou igual ao atual, inclusive variação de caixa | `400` |
| Bearer ausente/inválido/expirado/de outro propósito, conta não elegível ou senha atual incorreta | `401` |
| Novo endereço já utilizado por outra conta, sem distinção de caixa | `409` |
| Cooldown compartilhado de e-mail ainda vigente | `429` |

O cooldown de 30 segundos é reservado antes de bloquear a conta, evitando conflito
com a transação independente de `EmailCooldownService`. Tentativas com DTO válido
consomem esse intervalo mesmo quando a senha está incorreta, o endereço é igual ou
está ocupado. Validações de DTO e autenticação HTTP ocorrem antes dessa reserva.
Uma rejeição por cooldown não substitui a solicitação vigente.

Uma nova solicitação aceita recebe UUID novo, substitui a anterior e inicia sua
própria validade de 15 minutos. Não há reserva exclusiva do endereço enquanto ele
está pendente. A confirmação verifica novamente a unicidade.
O fluxo de cadastro pendente em `PATCH /api/auth/update-pending-email` é independente.

## Confirmar a troca

`POST /api/auth/email-change-confirmation`, com `Content-Type: application/json`:

```json
{
  "token": "TOKEN_RECEBIDO_NO_EMAIL"
}
```

A autorização é o JWT recebido por e-mail, exclusivamente no corpo. A operação
não exige sessão, cookie ou Bearer de acesso; um cabeçalho `Authorization` existente
é ignorado nesta rota. Não enviar o token na query string.

Resposta `200 OK`:

```json
{
  "message": "Email changed successfully. Please log in again.",
  "requiresLogin": true
}
```

Após bloquear e recarregar o usuário, o serviço verifica novamente elegibilidade,
propriedade, vigência, consumo, formato do endereço e unicidade sem distinguir caixa.
O consumo da solicitação, a alteração do e-mail por JPA, a revogação de todos os
refresh tokens e o evento de aviso ao endereço antigo compartilham a mesma transação.
Qualquer falha reverte esses quatro efeitos.

| Situação | HTTP |
| --- | --- |
| Troca confirmada | `200` |
| Token ausente, vazio, acima de 4096 caracteres, inválido, expirado, de outro propósito, sem metadados obrigatórios, substituído ou consumido | `400` |
| Conta removida/inativa/não verificada, autorização de outra conta, solicitação expirada ou novo endereço inválido | `400` |
| Endereço ocupado na confirmação, inclusive por corrida concorrente | `409` |

Somente a violação do índice `uk_users_email_ci` com SQLSTATE `23505` é convertida
em conflito de e-mail. Outros erros de persistência não recebem `409` indiscriminadamente.
Uma disputa sobre a mesma autorização produz um sucesso e uma rejeição por consumo.
Reutilizar uma autorização consumida não revoga sessões criadas após a confirmação.

### Sessões e envio da notificação

O usuário deverá fazer novo login com o novo e-mail ou com seu handle, usando a mesma
senha. O endereço antigo deixa de identificar essa conta. A conta permanece ativa e
verificada, e o nome de exibição do perfil é preservado.

Todos os refresh tokens anteriores são revogados. Access tokens já emitidos continuam
válidos até sua expiração, por até 15 minutos, conforme a política atual. A resposta
não emite credenciais novas. A futura tela deverá descartar o access token em memória
e encaminhar ao login; um cookie de refresh antigo será rejeitado na renovação.

`200` confirma a troca e o enfileiramento do aviso, não a entrega SMTP. O despachante
envia o aviso ao destinatário antigo persistido no evento, mesmo se outra solicitação
substituir a anterior. Falha SMTP mantém a troca confirmada e permite retentativas;
o aviso não contém token de autorização.

## Confirmação enviada por e-mail

O remetente gera JWT assinado com `purpose: email_change`, usuário em `sub`,
identificador da solicitação em `jti` e emissão/expiração originais persistidas.
Retentativas não prorrogam a autorização. Ele não serve como Bearer `access`,
retificação de cadastro ou autorização de senha.

O link aponta para a futura página `confirm-email-change.html`, usando fragmento
`#token=...` para não colocar o token na query HTTP. A página, ainda fora do escopo,
deverá apresentar confirmação explícita e enviar a autorização ao endpoint acima.
Abrir o link não altera o e-mail automaticamente. A página deve remover o fragmento
da URL após capturar a autorização e tratar `400` orientando nova solicitação nas
configurações; em `409`, o usuário deverá escolher outro endereço.

Uma solicitação pode ser substituída enquanto um envio já está em curso; o link
antigo deverá ser rejeitado pela confirmação por não corresponder à solicitação
vigente. O remetente descarta eventos já identificados como obsoletos antes do SMTP.

Coleção importável: [sonarfy-email-changes.postman_collection.json](sonarfy-email-changes.postman_collection.json).
Configurar variáveis somente no ambiente local e executar sobre conta de teste.
Os casos de senha incorreta/endereço ocupado exigem cooldown livre; a coleção
documenta essa condição. Não exportar senhas, tokens ou relatórios com credenciais.

## Validação

`EmailChangeRequestPostgresTest` exercita HTTP com segurança, serviços, JWT,
PostgreSQL e `JpaTransactionManager` reais. O transporte de e-mail é simulado.
Inclui validações, conflitos, cooldown/substituição, autenticação, concorrência,
rollback da solicitação quando o enfileiramento falha, propósito/expiração do JWT,
retentativa e descarte de eventos obsoletos.

`EmailChangeConfirmationPostgresTest` exercita também o fluxo completo de solicitação
até confirmação/login, replay, expiração, troca de propósito, vínculo com o usuário,
unicidade concorrente, disputa sobre a mesma autorização e rotação concorrente de
refresh. Falhas no flush e no enfileiramento verificam o rollback conjunto de
`User`, consumo JDBC, sessões e aviso. O transporte SMTP permanece simulado.
