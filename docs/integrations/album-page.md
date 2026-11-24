# Página do álbum

A captura Figma fornecida pelo usuário define capa, nome, artista, lançamento,
quantidade de faixas, média, total de avaliações, ação Avaliar/Avaliado, link para
Spotify, compartilhamento e avaliações da comunidade com ordenação.
Curtidas e comentários ficam fora do escopo.

## Situação da entrega

O usuário aceitou o escopo backend em 24/11/2026, após a entrega de detalhes,
agregados, catálogo e comunidade paginada. A implementação final segue para
review, CI e merge da branch `feat/album-community-reviews`.
A migration 019 ainda precisa ser aplicada ao banco local; 017 e 018 já foram
aplicadas. Esses estados não devem ser confundidos com disponibilidade da última
alteração na `main` ou com integração visual concluída.

Testes automatizados: 736 executados, 151 ignorados, zero falhas. Postman contra
servidor real e banco descartável: 23 requisições e 48 asserções, zero falhas,
entre comunidade vazia e preenchida. A validação anterior com Spotify real
continua registrada no [contrato](../api/albums.md). A comunidade não exige novas
chamadas ao provedor. Não houve nova validação visual nem alteração do frontend.

## Entrega 1 — consulta interna ao Spotify

O [contrato de álbum](../api/albums.md) descreve o cliente interno implementado.
Não disparar sincronização de Ecos como substituto para buscar um álbum desconhecido.

## Entrega 2 — catálogo local e enriquecimento

O serviço interno agora consulta o banco primeiro. Se o álbum faltar ou ainda não
tiver o total oficial de faixas, consulta o Spotify e persiste metadados na mesma
identidade usada por Ecos e avaliações. Depois do enriquecimento, consultas locais
não obtêm credencial nem acessam o provedor. Não há refresh periódico do catálogo.

A migration `017_add_album_total_tracks.sql` deve preceder o uso desse serviço em
bancos existentes. Registros anteriores continuam válidos com contagem nula até
serem enriquecidos; não representar essa ausência como zero faixas.

O resolver recebe um supplier de token, acionado apenas quando precisa acessar
o Spotify. A entrega 3 integra esse supplier à credencial da aplicação.

## Entrega 3 — detalhes e agregados

Consuma `GET /api/albums/{spotifyId}` com Bearer Sonarfy. O
[contrato completo](../api/albums.md#detalhes-do-álbum) define metadados, média e
total. Não é necessário conectar a conta Spotify pessoal para abrir a página.

- Mapear `name`, `coverUrl`, `artist.name`, `releaseOn`, `releasePrecision`
  e `totalTracks` ao cabeçalho. Respeitar precisão de ano/mês/dia, sem inventar
  dia ou mês em datas incompletas. A capa pode ser nula.
- Usar `averageRating / 2` para as estrelas; `reviewCount` é o total elegível.
  Média nula representa ausência de avaliações, diferente de média zero.
- Usar `/api/albums/{spotifyId}/reviews/me` para Avaliar/Avaliado. Um 404 dessa
  rota não significa que a nova consulta de detalhes também retornará 404.
- Após criar, editar ou excluir a avaliação própria, invalidar os detalhes para
  atualizar a média e a contagem; considerar também os fluxos de Coleção e feed.
- Diferenciar 401 Sonarfy (sessão) de 502/503/504 (falha da integração).
  Em 503, respeitar `Retry-After`, quando presente. Não iniciar OAuth pessoal
  em resposta a erro da credencial da aplicação e não criar loops de retry.
- A leitura pode buscar e persistir o catálogo; exibir carregamento. Um álbum
  não encontrado retorna 404, distinto de provedor indisponível.

Reiniciar o estado ao trocar de álbum ou conta. Busca por nome continua fora do
escopo. O frontend não foi alterado nesta entrega.

## Entrega 4 — avaliações da comunidade

Após resolver os detalhes, consultar
`GET /api/albums/{spotifyId}/reviews?sort=recent&limit=20` com Bearer Sonarfy.
A lista consulta somente o banco; desconhecido retorna 404, sem tentar importar
catálogo. Encadear depois dos detalhes evita uma corrida na primeira abertura.

- Usar `reviewId` como identidade do item e `author` como identidade pública do
  autor. Renderizar `rating / 2` estrelas, texto opcional e datas em UTC convertidas
  para o fuso da interface. Uma nota sem texto continua sendo uma avaliação.
- Mapear o seletor: Mais recentes → `recent`; Mais antigas → `oldest`;
  Maior nota → `highest`; Menor nota → `lowest`.
- Enviar `nextCursor` como `cursor` sem modificar; nulo encerra. Não calcular
  cursores ou aplicar ordenação somente sobre os itens já carregados.
- Troca de álbum, conta ou ordenação reinicia a lista. Após mutações próprias,
  invalidar detalhes e reiniciar a lista; não reutilizar cursores de outro contexto.
  Na ordenação por nota, edições concorrentes podem mover itens entre páginas.
- Resposta vazia de álbum conhecido é 200; parâmetros inválidos são 400.
  Sessão inválida é 401 e álbum desconhecido é 404. O total da comunidade e a
  média continuam vindo dos detalhes, sem contar apenas a página carregada.
- Leitura não concede edição/exclusão de avaliações de terceiros. Não preencher
  likes, comentários ou contadores sociais a partir de fixtures.

O backend da página passa a cobrir o escopo funcional acordado; a integração
visual e sua validação permanecem no trabalho separado de frontend.

## Regras de apresentação definidas

- O backend mantém notas de 0–10. A interface divide por dois para representar
  estrelas de 0–5: nota 10 corresponde a 5 estrelas; nota 2 corresponde a 1 estrela.
- Avaliações sem texto também aparecem na comunidade.
- Ordenações: mais recentes, mais antigas, maior nota e menor nota.
- Reutilizar o [fluxo de avaliação própria](reviews.md) para criar/editar/excluir.
- Usar o `spotifyId` para o link `https://open.spotify.com/album/{spotifyId}`.
  Compartilhar usa a URL da página Sonarfy, sem endpoint específico no backend.
- Não preencher agregados ou total de faixas a partir de fixtures, contagens de
  Ecos ou apenas uma página de resultados.

As regras globais de atividade do Sonarfy devem orientar a implementação visual.
O frontend permanece somente leitura no escopo de trabalho deste repositório.
