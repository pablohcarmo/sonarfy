# Perfil — primeira entrega de backend

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

PUT `/api/profile/me` permanece legado e retorna dados privados de conta; não
reutilizar a resposta como perfil público. Edição textual e upload não fazem parte
desta entrega. O modal atual limita nome a 50 caracteres, mas a leitura deve preservar
nomes persistidos com até 60; essa diferença será resolvida na entrega de edição.
