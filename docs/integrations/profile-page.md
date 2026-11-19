# Perfil — consulta e edição textual

O frontend existente permanece demonstrativo e não foi alterado nesta entrega.
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

Não preencher contadores, listas ou `isFollowing` com valores demonstrativos ao
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
wallpaper continua pendente. Referências ficam no banco relacional. Não enviar URLs
`blob:` locais como mídia persistente.

A fatia de avatar está descrita em [mídia de perfil](profile-media.md). O adapter
deverá enviar o arquivo para POST `/api/profile/me/avatar` e reconciliar o ProfileDto
retornado. Não é necessário reenviar o nome/bio para associar a imagem ao perfil.
