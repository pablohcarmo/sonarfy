# Integrar a ação Avaliar

Para a seção Coleção com ordenações globais, filtro e contagens, usar o
[guia específico](profile-collection.md). As limitações de filtro/contagem citadas
abaixo continuam aplicáveis às rotas antigas `/reviews/me` e de avaliações por perfil.

Contrato: [avaliações próprias](../api/reviews.md). Esta entrega modifica apenas o
backend. Nenhum arquivo do frontend foi alterado. As telas atuais de perfil/feed
ainda usam fixtures para avaliações; esta API não substitui um feed social.

## Fluxo por álbum

1. Receba `album.spotifyId` em Ecos ou da identidade Spotify do álbum nas demais
   telas. Fixtures com IDs fictícios precisam ser substituídas por essa identidade;
   não envie título, nome do artista ou ID interno como chave.
2. Faça `GET /api/albums/{spotifyId}/reviews/me` com Bearer. `200`: abra edição com
   `reviewId`, nota e texto da resposta. `404`: abra criação; isso também pode
   significar que o álbum não existe no catálogo local.
3. Formulário: nota obrigatória 0–10 em passos de 0,5; texto opcional até 255 pontos
   de código após trim Unicode. Resenha não é HTML; mantenha escape ao renderizar.
   As fixtures/renderers atuais usam cinco estrelas: para exibir, `estrelas = rating / 2`;
   para enviar uma seleção nessa escala, `rating = estrelas * 2`. O controle precisa
   conseguir representar quartos de estrela para cobrir todos os passos de 0,5
   da escala persistida. Não reduza silenciosamente a precisão para adaptar o renderer.
4. Criação: `POST /api/albums/{spotifyId}/reviews`, corpo `{"rating":8.5,"reviewText":"..."}`.
   `201`: use a avaliação retornada. `409`: outra aba/requisição já publicou;
   recarregue a própria avaliação e ofereça edição, sem sobrescrever automaticamente.
   Se a resposta se perder, consulte o GET antes de tentar publicar de novo.
5. Edição: `PUT /api/reviews/{reviewId}`, enviando a nota e o texto completos.
   Texto omitido/null apaga a resenha; nota omitida/null é erro. Preserve o rascunho
   em falhas. A última substituição confirmada prevalece entre abas.
6. Exclusão: `DELETE /api/reviews/{reviewId}` retorna `204`. `404` também exige
   atualizar o estado local. Se a resposta se perder, o GET por álbum resolve o estado.

Não solicite importação ao Spotify nem dispare sync de Ecos ao avaliar. POST com
álbum desconhecido retorna `404`; busca/persistência de catálogo fora de Ecos
depende da issue #52. Não prometa avaliar qualquer ID Spotify nesta etapa.
GET/POST não exigem conexão Spotify ativa, apenas catálogo local e conta elegível.

## Estado e invalidação

Use chave por `(usuário autenticado, spotifyId)` para a avaliação atual. Ao trocar
de conta ou encerrar sessão, descarte o cache. A API envia no-store, mas o estado
em memória também deve ser invalidado.

Após criar/editar/excluir:

- Ecos: `GET /api/echoes/albums` inclui `rated` e `reviewId` por item, sempre da
  conta autenticada. Use `rated ? 'Avaliado' : 'Avaliar'`. A avaliação de outra
  pessoa não deve influenciar esse botão. Preserve o clique em Avaliado para
  abrir a avaliação existente usando `album.spotifyId` e o GET próprio.
  Após POST/PUT, atualize o estado local com o `reviewId` retornado e `rated: true`;
  após DELETE, use `rated: false` e `reviewId: null`. Invalide/reconsulte a lista
  de álbuns ao retornar à tela, inclusive via `pageshow` quando houver restauração
  do histórico/bfcache. Não altere contagens ou datas de escuta e não refaça sync.
- Perfil próprio: invalide a coleção de avaliações (`GET /api/reviews/me`) e reinicie
  a primeira página para evitar duplicatas. Identifique cada item por `reviewId` e
  cada avaliação própria por álbum. Remova o item após DELETE.
- Página do álbum: invalide a avaliação própria. Agregados e lista de outras pessoas
  ainda não têm endpoint nesta entrega; não fabrique esses valores no adapter.
- Feed: qualquer estado local já carregado que represente essa avaliação deve ser
  invalidado. Não há endpoint/evento novo de feed, likes ou comentários.

`GET /api/reviews/me` retorna `items` e `nextBeforeId`; envie este último como
`beforeId` com `limit` até 50. A ordem é ID decrescente; não reordene cada página
isoladamente para simular ordenação global por nota. Filtros por resenha não estão
implementados. Consultas de terceiros usam a rota específica de perfil descrita
abaixo. Não exponha a coleção própria no perfil de
outro usuário. O DTO tem `album.name`, `album.coverUrl`, `album.artist.name`,
`rating`, `reviewText`, `createdAt` e `updatedAt`; adapte esses nomes aos renderers
atuais (`title`, `cover`, `review`) e não invente contadores sociais.

## Integração frontend verificada por leitura

Na conferência de 23/11/2026, `mapAlbumSummaryItem` em `js/echoes-http.js` e
`validateEcos` em `js/ecos-state.js` já preservam `rated` e `reviewId`. O renderer
de `js/ecos.js` escolhe Avaliado/Avaliar, aplica a classe de estado e possui
revalidação em `pageshow`. Essas mudanças foram realizadas em outra entrega;
este agente apenas consultou os arquivos, sem alterá-los ou executar seus testes.
O usuário confirmou o funcionamento das avaliações na aplicação. Essa confirmação
não substitui evidência individual de todos os cenários, temas e resoluções.

## Avaliações por perfil

Consuma `GET /api/profile/by-handle/{handle}/reviews` com Bearer para a seção de
avaliações de perfis próprios ou de terceiros. O [contrato](../api/profile-reviews.md)
retorna `author`, `items` e `nextBeforeId`. Use `author` como identidade pública
comum aos cards e o ID imutável para comparar autoria com a conta autenticada.
Disponibilizar leitura não autoriza editar ou excluir avaliações de terceiros.

Ao mudar o perfil, limpar itens e cursor antes da nova consulta. Para carregar
mais, enviar `beforeId=nextBeforeId` com o mesmo handle; null encerra a lista.
Não ordenar páginas isoladas por nota ou data. Em `404`, tratar o perfil como
indisponível; em falha de rede, oferecer nova tentativa sem mostrar lista vazia.
Após publicar, editar ou excluir uma avaliação própria, invalidar a primeira
página. Esta API não informa contagem total; não usar o tamanho da página como
total de álbuns avaliados. Não preencher likes/comentários a partir de fixtures.
Mapear nota 0–10, texto e álbum conforme as regras acima. O frontend foi apenas
consultado pela entrega de backend. A integração foi encaminhada separadamente
ao especialista em frontend, e sua validação foi confirmada pelo usuário abaixo.

### Confirmação de integração pelo usuário

Após encaminhar a integração da seção de avaliações ao especialista em frontend,
o usuário informou: “Testes feitos, tudo conforme.” A confirmação registra o
resultado dos testes da aplicação relatado pelo usuário, separadamente dos testes
automatizados e do Postman executados no backend.

Não foram fornecidos cenários individuais, capturas, temas, resoluções ou relatórios
de testes do frontend. Este registro não atribui cobertura específica a esses itens
nem aprovação de CI remota. Nenhum arquivo do frontend foi alterado por este agente.

## Próxima entrega

Notificações da plataforma, likes, comentários e respostas são funcionalidades
futuras, fora desta versão e dos seus critérios de conclusão. O feed pode ser
implementado sem essas interações. Não criar contratos, eventos ou dependências
preventivas para elas. Essa definição não altera e-mails transacionais de conta
e segurança. Consulte o [escopo da versão](../../README.md#escopo-desta-versão).

Consultas de avaliações por álbum e seus agregados exigem definição de escopo
própria; este documento não determina sua prioridade. A avaliação é persistida
imediatamente, sem rascunho; eventual atividade social decorrente da publicação
precisará de contrato próprio. Não existe outbox/evento preventivo nesta entrega.
