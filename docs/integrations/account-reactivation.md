# Integração de reativação

O frontend permanece somente leitura nesta entrega. O agente responsável por ele
deve adicionar uma ação explícita de reativação na área de autenticação, sem
deduzir ou revelar o estado da conta a partir de uma tentativa de login recusada.

Enviar login e senha via JSON ao contrato de [reativação](../api/account-reactivation.md),
com `X-Sonarfy-CSRF: 1`, sem Authorization e com `credentials: 'omit'`. Não usar
o wrapper de refresh automático nessa chamada. Em 204, limpar o access token
local e encaminhar ao login com confirmação de sucesso; a resposta não cria
sessão. Em 401, informar credenciais inválidas ou impossibilidade de reativar,
sem distinguir inexistência, inatividade ou falta de verificação. Manter senha
fora de URLs, armazenamento, logs e mensagens de erro. Bloquear envios duplicados
enquanto a chamada estiver pendente.

Se houver perda da resposta, nova tentativa com credenciais válidas é segura e
não gera outro aviso quando a conta já estiver ativa. Recuperação de senha
existente continua disponível para conta inativa verificada, sem reativá-la.
Não prometer recebimento imediato do aviso por e-mail.

## Roteiro manual, somente com contas de teste

Roteiro completo de referência; a execução parcial confirmada pelo usuário está
registrada abaixo. Não alterar flags de contas reais no banco por iniciativa do agente.
Como ainda não existe endpoint de desativação, preparar uma conta A inativa e
verificada somente em ambiente descartável por fixture de teste. Manter B ativa,
com relações existentes nos dois sentidos, uma relação removida e sessões de
teste anteriores à inatividade. Usar transporte SMTP de teste, nunca destinatários
reais por iniciativa do agente.

1. Importar a coleção JSON, definir `baseUrl`, `login` e `password` localmente.
   Executar requisições individualmente: a requisição de sucesso altera o estado.
2. Conferir login normal recusado para A inativa. Reativar com credenciais
   incorretas, e sem o cabeçalho CSRF: esperar 401 e 403, sem e-mail ou alteração.
3. Reativar A corretamente: 204 sem corpo, tokens ou cookie. A deve continuar sem
   sessão automática; fazer novo login para acessar o perfil.
4. Confirmar que os access tokens e refresh tokens antigos de A são recusados,
   inclusive após reativação; os de B continuam funcionando.
5. Verificar perfil visível, contagens restauradas para relações existentes,
   relação removida ausente e dados cadastrais/mídias preservados.
6. Conferir aviso "Conta reativada - Sonarfy" no transporte de teste. Repetir a
   reativação com credenciais corretas: 204, sem novo evento/aviso.
7. Em ambiente descartável, simular indisponibilidade SMTP: a reativação deve
   permanecer confirmada e o aviso deve ser retomado após restabelecer o envio.

## Validação automatizada

`AccountReactivationPostgresTest` usa MockMvc com a cadeia real de segurança,
serviços, BCrypt, JWT, JPA e PostgreSQL dedicado. O transporte de e-mail é
simulado; não comprova entrega SMTP real nem renderização em clientes de e-mail.
Não há dependência HTTP externa neste fluxo para simular via Postman.

Preparar um PostgreSQL descartável na porta 55452, banco
`sonarfy_reactivation_test`, usuário `reactivation_test`, senha exclusivamente de
teste `isolated-reactivation-test`, e importar `db/tables.sql` nesse banco vazio.
Executar:

```powershell
.\mvnw.cmd test '-Dtest=AccountReactivationPostgresTest' '-Dsonarfy.test.reactivation-postgres=true'
```

O teste não lê `application.properties`, valida o schema sem DDL automático e
limpa dados somente desse banco dedicado. A porta pode ser ajustada com
`-Dsonarfy.test.reactivation-port=<porta>`. A CI prepara o banco descartável,
verifica a migration incremental e habilita explicitamente esses testes em PRs.
Resultados locais, Postman manual, SMTP real e CI remoto são evidências distintas.

## Evidências locais em 21/11/2026

- Baseline após criar a branch: 727 reportados, 656 executados, 71 ignorados,
  zero falhas/erros.
- Na validação automatizada, migration 009 aplicada no PostgreSQL 17 descartável, após simular o
  schema anterior removendo a tabela nova e a coluna nova desse banco de teste.
  O schema consolidado também foi importado com sucesso em banco vazio.
- Testes direcionados finais: nove cenários PostgreSQL executados, zero
  falhas/erros. Cobrem HTTP pela segurança real, isolamento, tokens antigos,
  login/refresh novos, rollback, concorrência com login, senha, refresh e relações,
  envio simulado, retentativa e reservas concorrentes/abandonadas.
- Suíte completa com `sonarfy.test.reactivation-postgres=true`: 737 reportados,
  666 executados, 71 ignorados, zero falhas/erros. Os nove cenários PostgreSQL
  estão incluídos nesses 666; os demais testes PostgreSQL opcionais não foram
  habilitados nesta execução.
- No Windows, foi necessário apontar `JAVA_HOME` para o JDK 21 instalado e usar
  `-DargLine=-Djdk.attach.allowAttachSelf=true` para estabilizar a inicialização
  do Mockito no JBR. Nenhuma configuração global ou da aplicação foi alterada.
- Coleção JSON analisada sintaticamente, com quatro requisições. Integração
  frontend e CI remoto não executados. A evidência manual posterior está abaixo.
- Migration 009 posteriormente aplicada no banco local da aplicação com
  autorização explícita e backup, conforme [registro de persistência](../persistence.md).
  Nenhum commit, push ou PR realizado.

## Validação manual confirmada pelo usuário em 21/11/2026

Requisições executadas pelo usuário via Postman, com conta de teste cuja flag
`active` foi alterada por ele no banco local. O agente não executou essas
requisições nem inspecionou o destinatário ou o conteúdo recebido na caixa postal.

- Conta já ativa, com credenciais válidas: 204 No Content.
- Conta marcada como inativa pelo usuário: reativação retornou 204 No Content
  e o usuário confirmou o recebimento do e-mail de conta reativada.
- Login após reativação: 200.
- Access token antigo obtido no login e access token antigo emitido pelo refresh:
  ambos retornaram 401 na consulta autenticada após reativação.
- Cookie de refresh anterior: POST /api/auth/refresh retornou 401 com a conta
  novamente ativa, seguindo o ciclo orientado de login, inativação e reativação.
- Reativações repetidas com a conta já ativa: 204, sem novos e-mails recebidos.

Esses resultados comprovam os cenários relatados, incluindo recebimento real do
aviso. Não comprovam toda a matriz manual: credenciais inválidas, CSRF, isolamento
de outra conta, contagens e mídias, falha SMTP e preservação da sessão atual nas
repetições não foram confirmados manualmente nessa sequência. As coberturas
automatizadas permanecem evidência separada. Nenhum token, cookie ou senha foi
copiado para a documentação.
