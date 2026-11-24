# Integração da Coleção

Contrato: [Coleção do perfil](../api/profile-collection.md). Frontend consultado
somente em leitura; nenhuma implementação frontend faz parte desta entrega.

Em profile-utils.js, o menu demonstrativo usa recent/oldest/highest/lowest/written;
written filtra review.trim() e usa ordem recente. Ele ordena por createdAt; a API
usa ID de publicação, inclusive em empates, conforme a convenção existente.
No adapter HTTP atual, collection fica vazia e a seção de avaliações usa outra
rota. Adicionar carregamento separado para a Coleção, sem substituir essa seção.

Mapear recent/oldest/highest/lowest diretamente para sort. A opção written vira
sort=recent&writtenOnly=true; controles separados podem combinar qualquer sort
com writtenOnly. Nunca ordenar apenas a página recebida no cliente.

Reutilizar o mapeamento público de mapProfileReviews: reviewId → id do card,
album.spotifyId → identidade Spotify, album.name → title, coverUrl → cover,
album.artist.name → artist, reviewText → review (null vira vazio), datas preservadas.
author é comum à página. Não inventar likes, comentários ou permissões sociais.
Notificações da plataforma, likes, comentários e respostas são funcionalidades
futuras, fora desta versão; sua ausência não bloqueia a entrega de Coleção ou feed.
Não habilitar ações demonstrativas nem exibir contadores dessas interações como reais.
Nota exibida em estrelas = rating/2, preservando quartos de estrela e nota zero;
o rótulo demonstrativo de menor nota “1 ★” não representa o mínimo real da API.

Usar totalReviews para “N álbuns avaliados”, inclusive com filtro. Exibir
matchingReviews separadamente como resultados filtrados; nunca items.length como
total. nextCursor null encerra; carregar mais envia o token intacto no mesmo
contexto. Pode deduplicar por reviewId como proteção visual contra edições concorrentes,
mas somente reiniciar reconcilia omissões em ordenação por nota.

Manter estados independentes de carregamento inicial, carregamento adicional,
sucesso vazio e erro. matchingReviews=0 com totalReviews>0 significa nenhum texto
no filtro; totalReviews=0 significa coleção vazia. Falha de rede permite retry;
400 exige reiniciar cursor, 401 segue autenticação e 404 indica perfil indisponível.
Descartar respostas atrasadas de contexto anterior.

Limpar itens e cursores ao trocar perfil, ordenação, filtro ou sessão. Após criar,
editar ou excluir avaliação, invalidar páginas e totais e recarregar o início,
inclusive ao restaurar histórico/bfcache. Comparar IDs do autor e visitante para
oferecer edição; a API mantém a autorização final das mutações existentes.
