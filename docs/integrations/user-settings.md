# Configurações do usuário — dados pessoais

A tela de configurações usa os endpoints existentes, sem rota `/me/settings`.
Este documento cobre a entrega 1; os contratos de troca de e-mail, Spotify e
senha serão alinhados nas entregas seguintes. A [preparação da troca de e-mail](email-change.md)
documenta solicitação e confirmação, com ativação controlada e integração frontend pendente.
A [troca de senha](password-change.md) descreve o contrato do botão de envio e o
consumo futuro do link autenticado.

1. Consultar `GET /api/users/me` com Bearer `access` para carregar os valores.
2. Enviar `PUT /api/users/me` com os seis campos: `name`, `surname`, `handle`,
   `birthDate`, `country` e `city`, inclusive os que permanecerem iguais.
3. Em `200`, atualizar o estado local com o `UserDto` retornado.
4. Em `400`, manter as edições e corrigir os campos conforme
   [regras do contrato](../api/users-and-profile.md). Em `409`, solicitar outro
   handle. A operação é atômica em ambos os casos.
5. Em `401`, usar o fluxo existente de renovação de sessão descrito em
   [autenticação](authentication.md); se a renovação falhar, solicitar login.
   A edição em si não revoga sessões.

## Edição do handle

O username é editável e segue o mesmo formato do cadastro: de 1 a 15 caracteres,
com letras ASCII, números, `_`, `-` e `.`. Não enviar prefixo `@`, espaços ou acentos.
Cadastro e edição convertem letras para minúsculas antes de consultar unicidade e
salvar; o PUT retorna o valor canônico, que deve substituir o valor local do campo.
Exemplo: `Maria.Silva-9` é salvo e retornado como `maria.silva-9`.

É possível manter o próprio handle, inclusive enviando outra caixa. Outra conta
com o mesmo valor sem diferenciar maiúsculas/minúsculas causa `409`, sem atualização
parcial dos demais campos. Formato inválido causa `400`. ID, e-mail, senha e sessões
permanecem os mesmos. Login aceita a nova identificação; o handle anterior deixa
de identificar a conta quando o valor é alterado além da caixa.

A alteração não migra handles existentes em lote. Não há endpoint novo nem migration.
A localização e a revisão da data de nascimento pertencem às próximas entregas.

### Evidências da normalização do handle — 17/11/2026

- Suíte imediatamente após criar a branch: 578 reportados, zero falhas/erros e
  57 ignorados (`.\mvnw.cmd test`).
- Testes direcionados (`PersonalDataIntegrationTest,UserServiceTest`): 75 reportados,
  zero falhas/erros e um ignorado.
- Suíte completa final: 595 reportados, zero falhas/erros e 57 ignorados.
- Cobertura HTTP com segurança, serviços e JPA reais sobre H2 isolado: normalização
  no cadastro e edição, manutenção do próprio handle, caracteres permitidos e limite
  de 15, rejeição atômica de entradas inválidas, conflito sem diferenciar caixa,
  login com o handle atualizado e preservação de access e refresh existentes.
- Transporte de e-mail simulado. Sem envio SMTP real, execução manual no Postman,
  validação no navegador ou execução PostgreSQL nesta entrega. O teste concorrente
  PostgreSQL existente permanece opt-in e foi ignorado; nenhuma migration foi aplicada.
- Coleção JSON analisada sintaticamente; exemplo em caixa mista mantém a asserção
  de resposta em minúsculas. Código e testes somam 87 linhas alteradas.
- `UpdateUserDto` já é um record. Nenhum DTO precisou ser alterado para compartilhar
  a normalização no serviço. Contratos JSON e demais regras cadastrais preservados.

### Validação manual do handle relatada pelo usuário

- Entrada com 16 caracteres: 400; manutenção do próprio handle: 200.
- Prefixo `@` e espaços: 400; letras, números, hífen, underscore e ponto: 200.
- Handle ocupado por outra conta: 409.
- Consulta confirmou persistência em minúsculas após edição em caixa mista.
- Login com o novo handle em minúsculas e maiúsculas: 200; handle anterior: 401.
- Login seguido de edição do handle e refresh da sessão: 200. Nova edição
  autenticada após o refresh também retornou 200.
- Consultas com o access token anterior à edição e com o emitido após o refresh:
  ambas retornaram 200, confirmando a preservação do acesso pelo ID imutável.

Resultados executados manualmente pelo usuário no Postman. Tokens, senhas e
identificadores da conta de teste foram omitidos deste registro. Cadastro em caixa
mista e seus conflitos foram cobertos automaticamente, sem validação manual relatada.

Nome/sobrenome são independentes do nome de exibição do perfil (`displayName`).
O cadastro gera seu valor inicial; a atualização de dados pessoais o preserva.
Não enviar `displayName` no PUT. Sua edição pertence à página de perfil e fica
fora desta entrega, assim como uma representação própria para consulta do perfil.
Avatar, capa e biografia pertencem ao `UpdateProfileDto`; não incluir esses campos
na atualização de dados pessoais.

## Consumidor inspecionado

Em `C:/Projetos/sonarfy-frontend`, `pages/account.html` carrega `js/account.js`.
O script ainda preenche dados fixos e simula o salvamento sem chamadas HTTP.
Handle e país estão bloqueados na interface atual; a integração futura deverá
permitir sua edição conforme o contrato aprovado. O cadastro envia os seis
campos, inclusive país resolvido a partir da cidade.

Nenhum arquivo do frontend foi alterado e não houve validação no navegador.

## Validação reproduzível

Executar `PersonalDataIntegrationTest` normalmente cobre HTTP com segurança,
serviço e JPA reais sobre H2 isolado. Transporte de e-mail é simulado e não é
acionado por esta operação. Executar `UserServiceTest` verifica também que erros
de outros índices/SQLSTATEs não são classificados como conflitos de handle.

Para PostgreSQL, criar um contêiner **descartável separado** e importar
`db/tables.sql` nele. Nunca apontar esses testes ao banco da aplicação: o setup
remove usuários e sessões e altera o default de uma coluna. Executar:

```powershell
.\mvnw.cmd '-Dtest=PersonalDataIntegrationTest' `
  '-Dsonarfy.test.postgres=true' '-Dsonarfy.test.schema-mode=none' `
  '-Dsonarfy.test.jdbc-url=jdbc:postgresql://localhost:55439/sonarfy_personal_data_validation' `
  '-Dsonarfy.test.db-user=postgres' test
```

A corrida usa uma barreira no ponto da consulta prévia, consultando o banco para
confirmar ausência do handle em ambas as transações. As duas requisições HTTP
então disputam o índice real com variações de caixa. O teste exige exatamente
um `200`, um `409` e ausência de alteração parcial na conta perdedora.

A coleção JSON versionada em `docs/api/` é importável no Postman. Configurar
um Bearer temporário em variável local e executar somente sobre conta de teste:
os requests de PUT alteram seus dados. Não exportar variáveis preenchidas,
tokens ou relatórios com cabeçalhos de autenticação.

## Evidências da entrega 1

Em 16/11/2026:

- Suíte após a troca de branch: 504 testes reportados, zero falhas/erros, 29 ignorados.
- Suíte completa após a implementação: 531 reportados, zero falhas/erros, 30 ignorados.
  O cenário PostgreSQL é opt-in e fica ignorado nessa execução padrão.
  Suíte repetida com o mesmo resultado após corrigir a preservação de
  `displayName`: os testes HTTP agora verificam que um nome de exibição
  personalizado permanece igual ao alterar nome/sobrenome, inclusive nos limites.
- `PersonalDataIntegrationTest` sobre PostgreSQL 17.9 descartável, com
  `db/tables.sql`: 24 testes, zero falhas/erros e nenhum ignorado. Inclui corrida
  concorrente pela cadeia HTTP real, com exatamente um sucesso e um conflito.
- Coleção JSON lida e validada sintaticamente; execução automatizada da coleção
  no Postman não realizada.
- Validação manual no Postman realizada pelo usuário: consulta após login,
  atualização de handle/cidade e confirmação por novo GET; handle ocupado com
  variação de caixa rejeitado com `409`; idade inferior a 13 anos rejeitada com
  `400`. Nos dois erros, novo GET confirmou os dados e a auditoria inalterados.
  Login com o novo handle também confirmado. Credenciais e tokens não foram
  incluídos nas evidências versionadas.
- Sem chamadas a provedores reais ou validação no navegador; frontend não alterado.
- Nenhuma migration criada ou aplicada. O agente não alterou dados no banco da
  aplicação; as atualizações manuais acima foram realizadas pelo usuário.

O diff de código ultrapassa o alvo de aproximadamente 300 linhas devido à remoção
dos getters/setters na conversão dos dois DTOs para records e à cobertura HTTP
e concorrente exigida. As alterações pertencem à mesma correção de comportamento;
separar os testes deixaria o novo contrato sem suas garantias de regressão.
