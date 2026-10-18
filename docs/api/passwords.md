# Contrato de recuperação e troca de senha

Base local de exemplo: `http://localhost:8081` (porta da configuração local consultada).
Ajuste `baseUrl` para seu ambiente. O projeto declara Java 21, Spring Boot 4.1.0
e java-jwt 4.4.0 no `pom.xml`.

A coleção oficial deste fluxo é [sonarfy-passwords.postman_collection.json](sonarfy-passwords.postman_collection.json).
Importe-a no Postman. As pastas locais `postman/` e `.postman/` não são a referência
oficial deste fluxo e permanecem ignoradas pelo Git. Use somente contas de teste.
Os valores e tokens da coleção são placeholders; substitua-os localmente.

## Solicitar recuperação

`POST /api/auth/forgot-password` — público, `Content-Type: application/json`.

```json
{"email":"conta-teste@example.com"}
```

`email` é obrigatório, não pode ser vazio ou conter somente espaços e precisa ter
formato de e-mail. O controller recebe `ForgotPasswordDto` via `@RequestBody`.
Enviar somente `?email=...` na URL não atende a esse contrato.

Para entrada válida, os cenários previstos (conta inexistente, não verificada,
reserva recusada ou falha conhecida de entrega) retornam HTTP 200 e texto:

```text
If an eligible account exists, you may receive password reset instructions. Please check your inbox.
```

Isso não confirma existência de conta, envio ou entrega. Entrada inválida retorna
400. JSON ausente ou malformado também é rejeitado; tipo de conteúdo incompatível
pode retornar 415. Falhas inesperadas de infraestrutura ou programação não são
convertidas indiscriminadamente em sucesso.

## Redefinir senha esquecida

`POST /api/auth/reset-password` — público, `Content-Type: application/json`.

```json
{
  "token": "SUBSTITUA_PELO_TOKEN_DE_RECUPERACAO",
  "newPassword": "SenhaDeTesteNova123!",
  "confirmNewPassword": "SenhaDeTesteNova123!"
}
```

O `ResetPasswordDto` exige os três campos não vazios. A nova senha deve ter ao menos
8 caracteres; o serviço exige confirmação igual e senha diferente da atual.
Não se exige `currentPassword`: o usuário está recuperando uma senha esquecida.

Use o token recebido por e-mail, de finalidade `password_reset`, válido por
15 minutos. Tokens de troca autenticada têm finalidade separada e são rejeitados
neste endpoint. Não use token de ativação ou de retificação de cadastro.

Sucesso: HTTP 200, corpo em texto:

```text
Password reset successfully. You can now log in.
```

Erros previstos: 400 para campos inválidos, confirmação divergente, senha repetida,
token inválido/expirado/sem emissão ou já invalidado pelo corte temporal; 404 se o
usuário identificado por um token válido não existir. Os corpos de erro dependem
do tratamento de erros do Spring: não há aqui promessa de um formato JSON estável.

## Solicitar token para troca autenticada

`POST /api/users/me/password-change-request` — exige identidade autenticada,
sem corpo. O controller usa `Principal.getName()` para identificar o e-mail da conta.
O token enviado possui finalidade `password_change`, distinta de
`password_reset`; cada endpoint aceita somente o token emitido para seu próprio fluxo.

Sucesso: HTTP 200, texto `Password change authorization email sent successfully. Please check your inbox.`
Essa mensagem indica que o transporte não reportou erro, não comprova entrega.
Conta não verificada retorna 403; cooldown retorna 429. Diferentemente da solicitação
pública, falhas de entrega não são ocultadas por uma resposta genérica neste fluxo.

## Trocar senha estando autenticado

`PUT /api/users/me/password` — exige identidade autenticada e
`Content-Type: application/json`.

```json
{
  "token": "SUBSTITUA_PELO_TOKEN_DE_AUTORIZACAO_DA_TROCA",
  "currentPassword": "SenhaDeTesteAtual123!",
  "newPassword": "SenhaDeTesteNova123!",
  "confirmNewPassword": "SenhaDeTesteNova123!"
}
```

O `ChangePasswordDto` exige os quatro campos não vazios, com ao menos 8 caracteres
na nova senha. O serviço confere identidade da conta, senha atual, confirmação e
diferença em relação à senha anterior. A validação de senha atual no serviço também
protege chamadas diretas que não passam pelo `@Valid` do controller.

Sucesso: HTTP 200, texto `Password updated successfully.`
Após a autenticação, erros previstos incluem 400 por campos/senhas/token inválidos,
403 se o token pertencer a outra conta e 404 se o usuário do token não existir.

### Limitação atual da autenticação HTTP

O `SecurityConfig` libera `/api/auth/**` e exige autenticação nas demais rotas,
com sessões stateless. Entretanto, não configura filtro de JWT de acesso,
HTTP Basic, form login ou outro mecanismo que estabeleça o principal HTTP.
Assim, o fluxo autenticado ainda não pode ser apresentado como executável de ponta
a ponta na aplicação atual.

O token no JSON autoriza a alteração da senha, mas não autentica a requisição HTTP.
O uso de Bearer em `/api/auth/update-pending-email` é uma validação específica
de token de cadastro nesse controller; não constitui login para `/api/users/**`.

A coleção mantém as requisições protegidas em uma pasta separada, sem inventar
credenciais ou cabeçalho Bearer. Executar esses exemplos com sucesso depende de
implementar/configurar a autenticação HTTP, fora desta atualização documental.
Os testes de controller fornecem um principal simulado; não comprovam login real.

## Cenários e evidências

### Decisão: DTOs separados

`ResetPasswordDto` e `ChangePasswordDto` representam entradas diferentes.
Na recuperação, a autorização é comprovada pelo token e não se exige a senha
esquecida. Na troca autenticada, `currentPassword` é obrigatório e validado
com `@NotBlank`, além das verificações de identidade e senha atual no serviço.

Um DTO único exigiria tornar esse campo opcional ou selecionar validações
condicionais por operação. Mantemos os contratos explícitos e a validação
compartilhada do token e da atualização da senha no serviço. A repetição dos
campos comuns é pequena e evita aceitar combinações de entrada inadequadas
para cada endpoint.

### Cobertura existente

| Cenário | Resultado esperado | Verificação existente |
| --- | --- | --- |
| Solicitação com e-mail válido | 200, resposta genérica | AuthControllerTest / ForgotPasswordFlowTest |
| E-mail ausente, em branco ou malformado | 400 | AuthControllerTest |
| Conta elegível, cooldown ou falha SMTP | Resposta pública uniforme, envio apenas quando autorizado | ForgotPasswordFlowTest |
| Recuperação sem senha atual | Permitida com token válido | UserServiceTest / PasswordResetIntegrationTest |
| Troca com campos obrigatórios inválidos | 400 após estabelecer principal | UserControllerTest |
| Senha atual incorreta ou identidade diferente | 400 / 403, sem consumir token | PasswordResetIntegrationTest |
| Confirmação divergente | 400, sem alterar senha | UserServiceTest / PasswordResetIntegrationTest |
| Mesmo token reutilizado ou disputado | No máximo uma alteração | PasswordResetIntegrationTest |

A ausência isolada de `currentPassword` decorre de `@NotBlank`; o teste atual de
DTO inválido do controller combina mais de um campo inválido. Não interpretar essa
cobertura como um teste HTTP isolado de cada campo.

Os testes de serviço e integração confirmam as regras de negócio; a validação
Bean Validation ocorre na entrada HTTP. Consulte o README para executar a suíte.
A coleção contém exemplos manuais, não uma certificação de execução contra SMTP
ou de autenticação real.

## Limites do token e do envio

A reserva de envio é compartilhada entre ativação e recuperação e permanece após
falha SMTP. Eventos de ativação são processados após commit, em memória e de modo
síncrono: não garantem entrega após reinício.

O token é rejeitado se sua emissão for anterior ou igual à última alteração de
senha. A precisão é de segundos; um token novo no mesmo segundo também pode ser
recusado. O mecanismo não revoga sessões de login, e o fluxo síncrono pode revelar
diferenças de duração nas respostas.

