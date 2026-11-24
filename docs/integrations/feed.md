# Integração do feed único

Contrato: [feed de atividades](../api/feed.md). Integração frontend implementada
na branch feat/feed-integration, commit `515aa1e`, conferido em 24/11/2026 com
árvore limpa. Esta tarefa consultou o frontend exclusivamente em leitura.
O aceite anterior do backend permanece registrado no contrato. Esta conferência
confirma código consumidor, sem comprovar merge, implantação ou teste integrado real.

## Adapter e contrato HTTP

js/feed-fixtures.js seleciona createHttpFeedAdapter de js/feed-http.js por padrão.
Fixtures exigem `?demo=true`; o host pode fornecer window.sonarfyFeedAdapter.
Falhas de rede não acionam fallback silencioso para demonstração.

- GET /api/feed usa Bearer, credentials: omit, cache: no-store e cancelamento.
  loadPage envia limit e cursor por URLSearchParams, preservando o token.
- GET /api/profile/me fornece avatar e identidade do visitante separadamente.
  Falha desses metadados não bloqueia a lista.
- O adapter admite limit 1–50 e substitui entradas inválidas por 20; a página usa
  20. O servidor continua rejeitando requisições inválidas com 400.

Não houve mudança de rota, parâmetros, campos ou status do contrato backend.
followingCount continua contando perfis seguidos elegíveis, sem total de publicações.

## Mapeamento e cards

O adapter normaliza items para items, preservando a ordem recebida:

| API | Card |
| --- | --- |
| reviewId | id como string e reviewId original |
| author.id/handle/displayName/avatarUrl | author.id como string, handle, displayName, avatar |
| album.spotifyId | album.id e album.spotifyId |
| album.name/coverUrl | album.title e album.cover |
| album.artist.name/spotifyId | album.artist e album.artistSpotifyId |
| rating | rating/2, zero a cinco, preservando quartos de estrela |
| reviewText | review; null vira string vazia para apresentação |
| createdAt/updatedAt | preservados; a data exibida usa createdAt |

feed.js usa lista única, sem abas/filtros. followingCount=0 não oculta avaliações
próprias. Texto é renderizado com escape HTML. O perfil tem link por handle;
o álbum usa interactiveAlbum: false e link externo Abrir no Spotify, sem destino
interno inexistente. Não há abertura de avaliação completa ou edição pelo card.
O convite Avaliar Álbum leva a echoes.html, salvo ação fornecida pelo host.

Curtir/comentar aparecem apenas como controles indisponíveis, com aria-disabled
e aviso local. socialUnavailable: true não dispara requisições nem fabrica liked,
likeCount, commentCount ou replyTo. Não há contadores, respostas ou notificações
disponíveis; esses controles não ampliam o contrato backend.

O adapter expõe updateReview/deleteReview pelas rotas PUT/DELETE existentes,
mas mountFeed não conecta esses métodos a ações nos cards. PUT reconverte
estrelas para 0–10 e envia texto ou null. DELETE trata 404 como item já ausente
no cliente; o servidor continua retornando 404 para ausência ou autoria de terceiro.

## Paginação, erros e concorrência

feed-state.js separa loadingInitial, loadingMore e refreshing. Carregar mais
envia nextCursor intacto, preserva ordem e elimina repetições contra os cards
já carregados por reviewId. Cursor null ou página adicional vazia encerra a
navegação sem apagar itens anteriores. Refresh substitui a lista somente após
receber uma primeira página válida; edições não reposicionam publicações.

Cada reinício incrementa a geração e cancela a requisição anterior. Respostas
são descartadas se geração, sessão ou cursor solicitado mudarem. Carregamentos
adicionais simultâneos são bloqueados. Logout/troca de sessão limpa o estado.

Primeira página vazia com followingCount=0 orienta avaliar/acessar o perfil;
com followingCount>0 informa ausência de avaliações disponíveis. Erros não viram
lista vazia. Rede/5xx permite retry; falha adicional preserva cards e cursor.
400 oferece Recarregar do início, sem significar expiração temporal do cursor
backend. 401 segue o cliente compartilhado de renovação/expiração da sessão.

## Atualização e invalidação

- Clique no logo reinicia o feed; não há inserção automática de novas publicações.
- pageshow com persisted revalida visitante e início no retorno por bfcache.
  Navegação nova carrega a primeira página na montagem.
- storage para sonarfy_reviews_dirty/sonarfy_following_dirty invalida o feed.
  Emissores em album-review.js, profile-dialogs.js e profile.js cobrem criação,
  edição, exclusão, follow e unfollow dos fluxos existentes.
- storage para sonarfy_token ou limpeza do storage reinicia a sessão;
  BroadcastChannel sonarfy_auth trata logout, além da ação Sair.

storage notifica outras páginas/abas da mesma origem. Não há eventos de servidor
ou atualização em tempo real; atualizar o início reconcilia alterações externas.

## Evidências e limitações

Inspecionados js/feed-http.js, feed-state.js, feed.js, feed-fixtures.js,
profile-views.js, emissores de invalidação, tests/feed-http.test.js e
docs/feed-page.md no commit `515aa1e`. Não foram executados testes, builds ou
instalações no frontend nesta conferência.

docs/feed-page.md registra, em 24/11/2026, 260 testes aprovados, oito combinações
de navegador para feed e 16 para perfil, Edge 158.0.4370.33 e Firefox 159.0,
desktop/mobile, claro/escuro. São resultados registrados pela entrega frontend,
não repetidos nesta tarefa. Os testes de navegador usaram HTTP interceptado,
headless e viewport mobile emulado; o backend estava indisponível na porta 8080.

Comunicação integrada do frontend com backend real, CI remota, merge, implantação
e dispositivos físicos permanecem sem verificação nesta conferência. As evidências
de PostgreSQL e HTTP real do backend estão separadas no contrato da API.
