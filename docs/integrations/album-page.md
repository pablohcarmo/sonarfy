# Página do álbum

A captura Figma fornecida pelo usuário define capa, nome, artista, lançamento,
quantidade de faixas, média, total de avaliações, ação Avaliar/Avaliado, link para
Spotify, compartilhamento e avaliações da comunidade com ordenação.
Curtidas e comentários ficam fora do escopo.

## Entrega 1 — consulta interna ao Spotify

O [contrato de álbum](../api/albums.md) descreve o cliente interno implementado.
Ainda não há endpoint novo para o frontend consumir. Não disparar sincronização
de Ecos como substituto para buscar um álbum desconhecido.

## Entrega 2 — catálogo local e enriquecimento

O serviço interno agora consulta o banco primeiro. Se o álbum faltar ou ainda não
tiver o total oficial de faixas, consulta o Spotify e persiste metadados na mesma
identidade usada por Ecos e avaliações. Depois do enriquecimento, consultas locais
não obtêm credencial nem acessam o provedor. Não há refresh periódico do catálogo.

A migration `017_add_album_total_tracks.sql` deve preceder o uso desse serviço em
bancos existentes. Registros anteriores continuam válidos com contagem nula até
serem enriquecidos; não representar essa ausência como zero faixas.

As próximas entregas fornecerão detalhes com agregados e lista paginada. A busca
por nome fica fora do escopo. A obtenção/renovação da credencial será integrada
ao endpoint; o resolver atual recebe um supplier de token, acionado apenas quando
precisa acessar o Spotify. Isso não define uma exigência de conexão Spotify do
usuário final. Não há endpoint novo para consumir nesta entrega.

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
