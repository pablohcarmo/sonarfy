# Reativação de conta

`POST /api/auth/reactivation` é uma ação explícita, pública, independente do login.
Exige `Content-Type: application/json` e `X-Sonarfy-CSRF: 1`; quando presente,
`Origin` deve pertencer à configuração CORS permitida.

```json
{"login":"email-ou-handle","password":"senha-atual"}
```

O identificador aceita e-mail ou handle, sem distinção de caixa, com `@` opcional
no handle. O ID é obtido das credenciais validadas, nunca de um ID enviado pelo
cliente ou de um Bearer. Não enviar Authorization nem cookies; eles não autorizam
esta operação. `rememberMe` não é usado. A senha deve respeitar o limite BCrypt de
72 bytes UTF-8, sem truncamento.

| Resposta | Significado |
|---|---|
| 204 | Credenciais válidas para conta verificada, agora ativa ou já ativa |
| 400 | JSON inválido ou login/senha ausentes ou em branco |
| 401 | Conta inexistente, senha incorreta, senha acima de 72 bytes ou conta não verificada |
| 403 | Cabeçalho CSRF ausente/incorreto ou origem recusada |

Sucesso não retorna corpo, dados privados, access token ou cookie de sessão.
As respostas da rota recebem `Cache-Control: no-store` pelo filtro CSRF.
O consumidor deve realizar um novo login. O login normal continua recusando contas
inativas; ele não reativa implicitamente. Tokens de cadastro, acesso, recuperação
ou troca de senha não substituem as credenciais.

## Transição e sessões

Com bloqueio pessimista na linha da conta e releitura do estado, o backend valida
a senha atual e `verified=true`. Se já ativa, retorna 204 sem revogar sessões,
incrementar versões ou gerar outro aviso. Se inativa, na mesma transação:

- Remove todas as famílias e históricos de refresh do usuário.
- Incrementa `access_token_version` e invalida autorizações Spotify pendentes.
- Define `active=true` e persiste o aviso ao e-mail verificado atual.

Reativação não recria relações removidas, não muda a senha, e-mail, mídias ou
conexão Spotify persistida. Relações existentes voltam a participar das contagens
de perfis elegíveis. Não há desativação por falta de acesso recente.

JWTs de acesso novos contêm `access_version`; o filtro exige igualdade com o
estado persistido. JWT legado sem a claim pertence à versão zero, sem prolongar
sua expiração. Após a reativação, nem JWTs antigos ainda não expirados nem refresh
tokens antigos voltam a funcionar. Login captura a versão antes da emissão da
sessão, e refresh a captura sob bloqueio: uma resposta concorrente anterior nunca
é promovida à versão nova. A emissão de refresh pelo login também exige, sob
bloqueio, a mesma versão que foi lida ao autenticar; login anterior à transição
não pode criar uma sessão depois dela. Não se altera a política anterior de access tokens em
trocas de senha/e-mail ou logout.

## Aviso transacional

A outbox `reactivation_mail_outbox` registra um evento por transição confirmada,
com destinatário capturado sob bloqueio. Rollback também remove o evento. O
remetente consulta eventos a cada 5 segundos por padrão, até 10 por passagem,
usando `app.reactivation.dispatch-delay-ms` para configurar o intervalo.

Reserva de dois minutos com `SKIP LOCKED` é confirmada antes do SMTP; falhas
adiam a próxima tentativa por cinco minutos e não desfazem a reativação. Reservas
abandonadas expiram. Os prazos usam o relógio do PostgreSQL. Só o detentor da
reserva pode confirmar ou reagendar o evento. O aviso usa o transporte SMTP já
existente e não contém senha ou tokens.

A entrega é **pelo menos uma vez**: falha após o SMTP aceitar e antes de registrar
a entrega, ou envio que ultrapasse a reserva, pode duplicar o aviso. Não há
garantia de chegada à caixa de entrada. Eventos entregues permanecem registrados;
expurgo e política de retenção não foram introduzidos nesta entrega.

## Persistência e limites

Preparar `009_add_account_reactivation.sql` após 008 em bancos existentes, antes
de iniciar a nova versão. Não executar sobre banco novo criado com o schema
consolidado atualizado. A aplicação não executa migrations automaticamente.
Sua execução no banco da aplicação exige autorização específica.

Esta entrega não expõe desativação, exclusão definitiva ou notificações internas.
Não implementa limitação geral de tentativas de autenticação; respostas genéricas
e BCrypt fictício para conta inexistente não substituem esse controle. Proteção
de força bruta e operação de múltiplas instâncias devem considerar o limite na
borda. Requisições já em andamento não são canceladas retroativamente.

Coleção: [sonarfy-reactivation.postman_collection.json](sonarfy-reactivation.postman_collection.json).
Integração e roteiro: [reativação](../integrations/account-reactivation.md).
