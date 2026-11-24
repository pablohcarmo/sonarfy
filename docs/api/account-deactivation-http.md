# Desativação de conta — HTTP e envio

O backend implementa solicitação, envio e confirmação, mas o fluxo é
**desabilitado por padrão**. `app.account-deactivation.enabled=true` habilita
os dois endpoints e o remetente. Ausência da propriedade ou `false` não registra
controllers ou remetente. As migrations 010 e 016 devem existir antes de habilitar.
Não há execução automática de migrations.

A página de confirmação e o adapter do frontend ainda não estão integrados.
Manter a opção desligada nos ambientes de usuários até essa integração e sua
homologação. A configuração foi habilitada somente em validação isolada.

## Solicitar

`POST /api/users/me/account-deactivation-request`, com Bearer `access` de conta
ativa e verificada e `Content-Type: application/json`:

```json
{"currentPassword":"SUBSTITUA_LOCALMENTE"}
```

Senha obrigatória e não vazia. Senha acima de 72 bytes UTF-8 é recusada sem
truncamento. O ID vem exclusivamente do principal autenticado e o destinatário
é o endereço verificado atual, recarregado sob bloqueio. Campos adicionais não
autorizam selecionar outra conta ou endereço.

Resposta `202 Accepted`:

```json
{"message":"Account deactivation request accepted for delivery.","expiresInSeconds":900}
```

O aceite confirma autorização e intenção de envio na mesma transação. Não
confirma entrega SMTP, não retorna token/link, não desativa a conta e não revoga
sessões. A validade começa na emissão, não na chegada da mensagem.

| Situação | HTTP |
| --- | --- |
| Solicitação aceita para envio | 202 |
| JSON inválido ou senha ausente/em branco | 400 |
| Bearer ausente/inválido/expirado/de outro propósito, conta inelegível ou senha incorreta/acima de 72 bytes | 401 |
| Origem recusada pelo CORS | 403 |
| Content-Type diferente de JSON | 415 |
| Cooldown compartilhado de e-mail vigente | 429 |

O cooldown de e-mail existente é reservado antes da transação de negócio.
Tentativas autenticadas com entrada válida mantêm essa reserva mesmo após senha
incorreta ou falha no enfileiramento. Validação de entrada e autenticação HTTP
ocorrem antes da reserva. Rejeição não substitui uma autorização vigente.
Nova solicitação aceita substitui a anterior e inicia seus próprios 15 minutos.

## Confirmar explicitamente

`POST /api/auth/account-deactivation-confirmation`, com
`Content-Type: application/json`:

```json
{"token":"TOKEN_RECEBIDO_NO_EMAIL"}
```

O token no corpo é a única autorização. Não exige sessão, Bearer ou cookie;
um cabeçalho Authorization presente é ignorado. Não usa o cabeçalho CSRF das
rotas de sessão, pois não é autorizado por cookie. CORS continua aplicado.
Não colocar o token na query string e não confirmar por GET ou abertura do link.

| Situação | HTTP |
| --- | --- |
| Desativação confirmada | 204, sem corpo |
| Token ausente, vazio, acima de 256 caracteres, inválido, expirado, futuro, substituído ou consumido | 400 |
| Conta removida, inativa ou não verificada | 400 |
| Origem recusada pelo CORS | 403 |
| Content-Type diferente de JSON | 415 |

Sucesso não emite access token, cookie ou nova sessão. Consumo, revalidação sob
bloqueio, revogação de todas as famílias/históricos de refresh, incremento de
versões de acesso e autorização Spotify e `active=false` compartilham a mesma
transação. Qualquer falha reverte todos os efeitos. Replay não revoga sessões
criadas depois de uma reativação explícita.

Dados pessoais, relações, avaliações, coleção, Ecos e conexão Spotify são
preservados. Regras existentes de elegibilidade ocultam a conta inativa onde
aplicáveis. Login normal não reativa; usar a reativação explícita e novo login.
JWTs antigos não voltam a funcionar depois desse ciclo. Não há exclusão definitiva.

As respostas dos dois caminhos recebem `Cache-Control: no-store`, inclusive
rejeições anteriores ao controller por autenticação ou CORS.

## Entrega, retentativas e link

O remetente consulta a outbox a cada cinco segundos por padrão, até dez reservas
por passagem. O intervalo pode ser ajustado por
`app.account-deactivation.dispatch-delay-ms`. Reserva e releitura são confirmadas
antes do SMTP; o envio não mantém transação de conta aberta.

O banco revalida solicitação, UUID, conta, destinatário, consumo, prazo e reserva
imediatamente antes do envio. Cada tentativa recebe um segredo aleatório de
256 bits e somente seu SHA-256 é persistido. A rotação preserva UUID, emissão e
expiração. O segredo transitório não deve ser registrado em logs ou relatórios.

Falha mantém a solicitação aceita e reagenda a intenção para 30 segundos depois.
Reserva abandonada expira em dois minutos. Somente o dono da reserva vigente
pode concluir ou reagendar. Retentativas não renovam os 15 minutos; se o prazo
acabar, será necessária uma nova solicitação autenticada.

O link usa `app.frontend-url` e `/confirm-account-deactivation.html#token=...`.
A futura página deve capturar e remover o fragmento, apresentar a confirmação
explícita e só então enviar o POST. Deve tratar 400 orientando nova solicitação.
Ainda não existe essa integração no frontend; o backend não altera seus arquivos.

Resultado SMTP incerto pode exigir outro link: uma retomada rotaciona o segredo
e invalida o anterior. Alterações de credenciais, consumo ou substituição durante
um SMTP já iniciado não recolhem mensagens em trânsito, mas tornam seu token
inutilizável. Não há garantia de entrega exatamente uma vez ou chegada à caixa
de entrada. Eventos obsoletos permanecem limitados a uma intenção por usuário;
expurgo não foi implementado.

## Validação

Coleção: [sonarfy-account-deactivation.postman_collection.json](sonarfy-account-deactivation.postman_collection.json).
Executar somente com contas de teste e ambiente isolado. Rodar a pasta
`1 Request`, obter o token na caixa SMTP de teste, preencher `deactivationToken`
localmente e rodar `2 Confirm`. A segunda pasta desativa e depois reativa
explicitamente a conta de teste, concluindo com novo login.

Configurar `baseUrl`, `login` e `password` no ambiente local. O login guarda
`accessToken` e `oldRefreshCookie` nesse ambiente para verificar a revogação.
Execuções CLI separadas precisam receber essas variáveis e o token no arquivo
local do ambiente; não exportar credenciais para o Git nem publicar relatórios
que as contenham. Usar `--no-report-events` nas execuções locais.

Testes automatizados usam segurança, serviços e PostgreSQL reais, com transporte
simulado. A execução integrada adicional usa API empacotada, Postman e receptor
SMTP local; não comprova entrega por provedor externo ou funcionamento da página
frontend. Operação, evidências e checklist estão no
[guia de integração](../integrations/account-deactivation.md).
