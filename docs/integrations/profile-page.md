# Perfil — consulta e edição textual

A seção de avaliações agora dispõe de leitura paginada autenticada para o próprio
perfil e terceiros: [integração](reviews.md#avaliações-por-perfil) e
[contrato](../api/profile-reviews.md). Cabeçalho e avaliações têm carregamentos
separados; as demais listas demonstrativas não passam a ser dados reais.

A integração de avaliações foi encaminhada ao especialista em frontend e o usuário
confirmou seus testes conforme o [registro de validação](reviews.md#confirmação-de-integração-pelo-usuário).
As referências a adapters demonstrativos abaixo descrevem a entrega original de
perfil, não uma nova conferência do estado atual do frontend. O frontend não foi
alterado por este agente.
O adapter de produção deverá usar GET `/api/profile/me` sem handle na URL e
GET `/api/profile/by-handle/{handle}` para um handle explícito, ambos com Bearer.
Não selecionar o usuário por e-mail ou por identificador fornecido no corpo.

Mapeamento de [ProfileDto](../api/profiles.md) para o adapter atual:

| Backend | Frontend |
| --- | --- |
| identity.id / handle / displayName | profile.id / handle / displayName |
| identity.avatarUrl / coverUrl / biography | profile.avatar / cover / bio |
| identity.location.city / country | profile.city / country |
| identity.joinedAt | profile.joinedAt |
| viewer.isSelf / canEdit | Propriedade e permissão da interface |
| viewer.isFollowing | profile.isFollowing |
| statistics.followers / following | profile.followers / following |

Contadores e `isFollowing` já possuem valores reais no contrato. Não preencher listas indisponíveis com valores demonstrativos ao
usar dados reais. A página atual exige todas as listas em `load`; a integração
futura precisará desacoplar o cabeçalho e os estados das seções indisponíveis.
ID é estável, handle é mutável; comparar IDs com tipos normalizados no adapter.

Em 401, tratar a sessão pelo fluxo de autenticação existente. Em 404, apresentar
perfil indisponível, sem afirmar se a conta existe ou foi desabilitada. Falhas de
transporte permitem retry. Não tratar falhas como coleção vazia. Cada carregamento
deve descartar o contexto do visitante anterior, sem cache compartilhado de viewer.

Para edição textual, enviar PUT `/api/profile/me` com `displayName` e `biography`,
usando `bio` do formulário para preencher `biography`. Ambos são opcionais: omitir
preserva o valor atual. Nome enviado deve conter texto após remover espaços Unicode
nas extremidades, com até 60 pontos de código, sem restrição de caracteres.
Bio aceita até 150 pontos de código; `null` ou `""` limpa. Reconciliar o estado com
o `ProfileDto` retornado. Em 400, preservar o rascunho; não há gravação parcial.

O frontend ainda precisa adaptar o limite atual de nome (50), `maxlength` em unidades
UTF-16 e mensagens, além de mapear a nova resposta do PUT. O backend não o altera
nesta entrega. Não enviar avatar/capa pelo PUT textual: são ignorados, preservando
as imagens existentes. Upload de avatar usa o endpoint separado com Azure Blob Storage;
wallpaper usa sua própria rota de upload. Referências ficam no banco relacional. Não enviar URLs
`blob:` locais como mídia persistente.

A fatia de avatar está descrita em [mídia de perfil](profile-media.md). O adapter
deverá enviar o arquivo para POST `/api/profile/me/avatar` e reconciliar o ProfileDto
retornado. Não é necessário reenviar o nome/bio para associar a imagem ao perfil.

Para capa, enviar o arquivo a POST `/api/profile/me/wallpaper` e mapear
`identity.coverUrl` para `profile.cover`. A imagem não é recortada no backend;
o frontend precisa definir o enquadramento responsivo e eventual ajuste visual de
posição. Essa posição ainda não é persistida pela API. Não usar coordenadas de
recorte de avatar para wallpaper. Ambos retornam o mesmo ProfileDto.

## Relacionamentos no cabeçalho

Esta entrega altera somente o backend. O adapter demonstrativo continua separado.
Após PUT/DELETE de relacionamento com 204, recarregar o perfil pelas rotas GET
para reconciliar isFollowing e contadores. Não usar a direção inversa para decidir
o botão. Não derivar dados reais das fixtures nem do estado do visitante anterior.
As contagens excluem contas inativas/não verificadas; PUT/DELETE permanecem sujeitos
à elegibilidade das duas contas. Não implementar desativação pelo frontend.

Roteiro manual com contas de teste: consultar o próprio perfil (isFollowing=false),
consultar B com token de A, seguir B, consultar B novamente (isFollowing=true,
followers incrementado) e consultar A (/me, following incrementado). Repetir o PUT
não altera contagens. DELETE seguido dos GETs restaura o estado anterior. Criar a
direção B→A separadamente e confirmar que ela não implica A→B. Verificar 401 sem
Bearer e com registrationToken; perfil inativo/não verificado continua 404.
Mudanças de active/verified devem ser testadas somente no PostgreSQL descartável,
conforme os testes automatizados. Não alterar o banco da aplicação para esse roteiro.
