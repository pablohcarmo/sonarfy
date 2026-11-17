# Contrato de recuperação e troca de senha

Base local de exemplo: `http://localhost:8080` (porta da configuração local consultada).
Ajuste `baseUrl` para seu ambiente. O projeto declara Java 21, Spring Boot 4.1.0
e java-jwt 4.4.0 no `pom.xml`.

A coleção oficial deste fluxo é [sonarfy-passwords.postman_collection.json](sonarfy-passwords.postman_collection.json).
Importe-a no Postman. As pastas locais `postman/` e `.postman/` não são a referência
oficial deste fluxo e permanecem ignoradas pelo Git. Use somente contas de teste.
Os valores e tokens da coleção são placeholders; substitua-os localmente.

Para conferir o acesso HTTP, configure `baseUrl` (padrão `http://localhost:8080`),
`email` e `currentPassword` de uma conta ativa e verificada e execute apenas a
pasta **Autenticação HTTP**. Ela faz login, salva `accessToken` e verifica o perfil
com Bearer, sem credencial e com token inválido. O teste de identidade compara
o e-mail do perfil com a variável `email`; use e-mail no login dessa sequência.
A coleção oficial usa `baseUrl`; a variável `app.base-url` de coleções locais
independentes não altera essas requisições.

As pastas de recuperação e troca de senha devem ser executadas separadamente,
com os tokens do e-mail preenchidos. Elas podem alterar a senha da conta.
O Bearer de acesso é herdado apenas pela pasta **Troca autenticada**; as rotas
públicas usam **No Auth**. Não versione o `accessToken` capturado nem credenciais reais.

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
sem corpo. O controller usa o ID autenticado para localizar a conta e seu e-mail atual.
O token enviado possui finalidade `password_change`, distinta de
`password_reset`; cada endpoint aceita somente o token emitido para seu próprio fluxo.

Sucesso: HTTP 200, texto `Password change authorization email sent successfully. Please check your inbox.`
Essa mensagem indica que o transporte não reportou erro, não comprova entrega.
Sem Bearer válido, ou com conta inativa/não verificada, a cadeia HTTP retorna 401.
Cooldown retorna 429, sem `Retry-After` ou prazo restante no corpo. A configuração
`app.email.cooldown-seconds` tem padrão de 30 segundos e a reserva é compartilhada
com ativação e recuperação; falha SMTP não libera essa reserva. Falhas conhecidas
de entrega propagam como erro de servidor (500 no tratamento padrão), sem formato
contratual de erro estável. Não interpretar esse erro como confirmação de entrega.

O e-mail textual existente contém o link:

```text
{app.frontend-url}/pages/account.html#password-change-token=TOKEN_DE_AUTORIZACAO
```

Barras finais da base são removidas antes de compor o caminho. Se a base já termina
em `/pages`, acrescenta-se somente `/account.html`, evitando `/pages/pages/`.
O fragmento não é
enviado ao servidor web na navegação. O futuro consumidor deve extraí-lo em memória,
limpar a URL e usá-lo apenas no JSON do PUT autenticado; não usar esse token como
Bearer nem encaminhá-lo para `/api/auth/reset-password`. A leitura do fragmento e
conclusão pela tela ainda dependem da próxima etapa do frontend.
O link não altera a senha automaticamente e não substitui o login.
Consulte o [guia de integração](../integrations/password-change.md).

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

### Autenticação HTTP

O `SecurityConfig` libera somente os métodos e caminhos públicos listados no
[contrato de autenticação](authentication.md) e exige autenticação nas demais
requisições, com sessões stateless. Faça login e envie o JWT de acesso como
`Authorization: Bearer <accessToken>` nas requisições autenticadas.
O filtro identifica pelo ID imutável do token de acesso e carrega a conta ativa e verificada.
Veja o [contrato de autenticação](authentication.md).

O token no JSON autoriza a alteração da senha, mas não autentica a requisição HTTP.
O uso de Bearer em `/api/auth/update-pending-email` é uma validação específica
de token de cadastro nesse controller; não constitui login para `/api/users/**`.

A coleção mantém as requisições protegidas em uma pasta separada; adicione o
Bearer de acesso ao executá-las. O token de autorização da operação continua
necessário no corpo dos endpoints de troca de senha. Os testes isolados de
controller usam principal simulado; `SecurityChainIntegrationTest` verifica
a autenticação HTTP com a cadeia real do Spring Security.

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
DTO inválido do controller combina mais de um campo inválido. A cobertura HTTP em
`SecurityChainIntegrationTest` verifica separadamente a ausência de `currentPassword`,
além de senha atual incorreta, confirmação divergente, senha repetida e curta.

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
recusado. Tanto a troca autenticada quanto a recuperação revogam todas as sessões
de refresh do usuário, incluindo históricos e sucessores, na mesma transação da
senha. O bloqueio pessimista da linha do usuário é compartilhado com a rotação:
se o refresh confirmar primeiro, seu sucessor será revogado; se a alteração de
senha confirmar primeiro, o refresh será recusado. Rollback restaura senha e
sessões conjuntamente; validação recusada não revoga sessões. Outros usuários
não são afetados. Access tokens já emitidos podem continuar válidos por até
15 minutos. O cliente deve descartar credenciais locais e fazer novo login.
O fluxo síncrono pode revelar diferenças de duração nas respostas.


## Cobertura da preparação do link autenticado

`SecurityChainIntegrationTest` cobre solicitação e conclusão com controllers,
segurança, serviços, JWT e JPA reais em H2 isolado. Apenas o transporte de e-mail
é simulado. Confere destinatário após atualização persistida do e-mail com o mesmo
ID, link no fragmento, validade de 900 segundos, cooldown, falha de envio mantendo
reserva, rejeição de Bearer inadequado, propósitos separados, validações de senha,
replay, revogação dos refresh e login com a nova senha. A atualização do e-mail
no preparo do teste não executa o fluxo de confirmação de troca de endereço.
A cobertura de concorrência e rollback existente permanece em
`PasswordResetIntegrationTest`; não equivale a uma corrida HTTP nova.
