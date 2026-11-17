# Troca de senha nas configurações

O botão deve reutilizar `POST /api/users/me/password-change-request`, sem corpo,
com `Authorization: Bearer <accessToken>`. Não enviar e-mail ou ID no payload.
O backend identifica a conta pelo ID imutável e usa o endereço persistido atual,
inclusive após confirmar uma troca de e-mail.

Em 200, o corpo é texto:
`Password change authorization email sent successfully. Please check your inbox.`
Exibir instrução para verificar a caixa de entrada; isso não comprova entrega SMTP.
A solicitação não altera a senha nem revoga sessões.

## Estados do botão

- Durante a requisição, impedir cliques duplicados.
- Em 200, iniciar cooldown local conforme a configuração do ambiente (padrão 30s).
- Em 429, informar para aguardar. Não há `Retry-After` nem tempo restante:
  um contador local é uma aproximação. O bloqueio pode resultar de envio de
  ativação/recuperação ou de tentativa que falhou no SMTP.
- Em 401, seguir a renovação de sessão existente e solicitar login se ela falhar.
- Em erro de servidor/rede, não afirmar envio ou entrega; permitir nova tentativa
  respeitando o cooldown que pode ter sido reservado.

Erros não possuem formato JSON estável; tratar primeiro o status HTTP.

## Destino e conclusão futura

O e-mail usa texto simples e aponta para
`{app.frontend-url}/pages/account.html#password-change-token=TOKEN`.
Nenhum template novo foi criado. O token tem propósito `password_change` e
validade de 15 minutos. A recuperação pública continua usando `password_reset`.
Se `app.frontend-url` já termina em `/pages` (com ou sem barra final), o backend
acrescenta somente `/account.html`. Bases na raiz recebem `/pages/account.html`.

Na próxima etapa do frontend:

1. Ler `password-change-token` do fragmento em memória e limpar a URL com
   `history.replaceState` antes de instrumentar analytics ou registrar navegação.
   Não persistir ou registrar token/link em logs, relatórios ou armazenamento local.
2. Exigir sessão autenticada da mesma conta. Se for necessário login, preservar
   a autorização apenas em memória; se ela for perdida ou expirar, solicitar novo link.
3. Coletar senha atual, nova senha e confirmação. Enviar `PUT /api/users/me/password`
   com Bearer de acesso e JSON com `token`, `currentPassword`, `newPassword` e
   `confirmNewPassword`. O token do link não é credencial de login.
4. Em 200 (`Password updated successfully.`), limpar autorização e credenciais
   locais e solicitar novo login. Todos os refresh são revogados; access tokens
   já emitidos podem permanecer válidos por até 15 minutos.
5. Tratar 400 para campos/token/senhas inválidos ou replay, 403 para token de outra
   conta, 404 para conta do token inexistente e 401 para autenticação inválida.
   Nova senha exige ao menos oito caracteres, confirmação igual e diferença da atual.
   Erro de validação não consome um token que ainda seja válido.

O uso único é implementado por bloqueio e corte temporal da última troca:
após sucesso, todos os tokens de senha emitidos até esse instante são recusados.
Um token novo emitido no mesmo segundo também pode ser recusado. A abertura do
link não consome o token; somente a alteração bem-sucedida invalida autorizações.

## Validação e limites

`SecurityChainIntegrationTest,AuthEmailServiceTest` verificam o contrato HTTP com
segurança real e o conteúdo do e-mail. H2 é isolado e o transporte é simulado:
esses testes não comprovam SMTP real, entrega ou navegação.
`PasswordResetIntegrationTest` mantém cobertura transacional, replay e concorrência.

A coleção importável é `docs/api/sonarfy-passwords.postman_collection.json`.
Execute as pastas separadamente sobre conta de teste; o PUT altera a senha.
Preencha tokens e credenciais apenas em variáveis locais e não exporte valores.
PostgreSQL com criação/remoção de tabelas exige instância descartável separada.

O frontend foi somente consultado. O botão atual simula o envio e ainda não lê o
fragmento; a implementação do botão e a validação no navegador ficam para outra etapa.

## Evidências desta entrega — 17/11/2026

- Testes direcionados: 106 reportados, zero falhas/erros e nenhum ignorado.
- Suíte completa em build isolado: 575 reportados, zero falhas/erros e 57 ignorados.
  Os 17 novos cenários estão em `SecurityChainIntegrationTest`.
- A execução obrigatória de `.\mvnw.cmd test` foi iniciada imediatamente após criar
  a branch. Resíduos de classes antigas no build impediram uma linha de base limpa.
  `clean test` foi tentado, mas o Windows bloqueou a remoção de `target/classes`.
  Para validar sem interromper a aplicação, foi usada uma cópia temporária do POM
  alterando somente o diretório de build para `target/password-link-validation`.
  A cópia foi removida ao final; o POM do projeto permanece inalterado.
- Reprodução do build isolado, quando necessário:

```powershell
$validationPom = Join-Path (Get-Location) 'pom-password-link-validation.xml'
$pomContent = (Get-Content pom.xml -Raw).Replace('<build>',
  '<build><directory>${project.basedir}/target/password-link-validation</directory>')
[IO.File]::WriteAllText($validationPom, $pomContent, [Text.UTF8Encoding]::new($false))
.\mvnw.cmd -f pom-password-link-validation.xml test
Remove-Item -LiteralPath $validationPom
```

- Coleção JSON v2.1 analisada sintaticamente; conferidos Bearer herdado, POST sem
  corpo, PUT e scripts de sucesso. O agente não executou a coleção no Postman;
  as requisições manuais executadas pelo usuário estão registradas abaixo.
- O agente não executou PostgreSQL, SMTP real, navegador ou CI remoto, nem
  migrations ou operações no banco da aplicação. O usuário validou o envio real
  de e-mail e alterou a senha da conta de teste pelas requisições descritas abaixo.
- Código de produção e testes antes da correção do caminho: 172 linhas alteradas (adições + remoções).
  Documentação e coleção JSON não entram nessa contagem.
- `README.md`, `docs/domain-decisions.md`, `tmp/` e `application.properties`
  preservados e fora da entrega. Sem commit, push ou criação de PR.

## Validação manual relatada pelo usuário

No Postman e na caixa de e-mail real, o usuário confirmou:

- Solicitação com 200 e recebimento do e-mail.
- Solicitação imediata com 429 e novo 200 após o cooldown.
- Troca autenticada com 200; replay recusado com 400.
- Refresh anterior recusado com 401.
- Login com senha antiga recusado com 401 e com a nova aceito com 200.
- Nova senha igual à atual recusada com 400; o mesmo token permitiu depois uma
  troca válida com 200, comprovando que a recusa não consumiu a autorização.

O link recebido revelou duplicação `/pages/pages/`, corrigida no backend para
bases que já incluem `/pages`. Após reiniciar o backend, o usuário confirmou um
novo link recebido com `/sonarfy-frontend/pages/account.html`, sem duplicação,
mantendo o token no fragmento. Nenhuma validação de navegador realizada.
Credenciais, tokens e links completos foram omitidos destas evidências.

Após a correção do caminho: suíte completa com 578 testes reportados, zero falhas/erros
e 57 ignorados, no mesmo build isolado. Diff de produção e testes: 181 linhas.
