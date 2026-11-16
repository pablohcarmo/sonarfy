# Solicitação de troca de e-mail de conta verificada

Este contrato corresponde ao segundo de três PRs. A confirmação será implementada
no terceiro. O fluxo permanece **desativado por padrão**: não habilitar
`APP_EMAIL_CHANGE_ENABLED` em ambiente de uso antes de a confirmação estar disponível.
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
está pendente. A confirmação futura deve verificar novamente a unicidade.
O fluxo de cadastro pendente em `PATCH /api/auth/update-pending-email` é independente.

## Confirmação enviada por e-mail

O remetente gera JWT assinado com `purpose: email_change`, usuário em `sub`,
identificador da solicitação em `jti` e emissão/expiração originais persistidas.
Retentativas não prorrogam a autorização. Ele não serve como Bearer `access`,
retificação de cadastro ou autorização de senha.

O link aponta para a futura página `confirm-email-change.html`, usando fragmento
`#token=...` para não colocar o token na query HTTP. A página, ainda fora do escopo,
deverá apresentar confirmação explícita e enviar a autorização ao endpoint do
terceiro PR. Abrir o link não deve alterar o e-mail automaticamente.

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

O rollback completo da futura confirmação — alteração JPA de `User`, consumo JDBC,
revogação de sessões e notificação ao endereço antigo — continua obrigatório no PR 3.
Nenhum endpoint de confirmação está implementado neste recorte.
