# Página do álbum

A captura Figma fornecida pelo usuário define capa, nome, artista, lançamento,
quantidade de faixas, média, total de avaliações, ação Avaliar/Avaliado, link para
Spotify, compartilhamento e avaliações da comunidade com ordenação.
Curtidas e comentários ficam fora do escopo.

## Entrega 1 — consulta interna ao Spotify

O [contrato de álbum](../api/albums.md) descreve o cliente interno implementado.
Ainda não há endpoint novo para o frontend consumir. Não disparar sincronização
de Ecos como substituto para buscar um álbum desconhecido.

As próximas entregas fornecerão persistência quando faltar álbum no catálogo,
detalhes com agregados e lista paginada. A busca por nome fica fora do escopo.
A obtenção/renovação da credencial para esse fluxo ainda será integrada;
o cliente desta entrega recebe um token válido do chamador.

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
