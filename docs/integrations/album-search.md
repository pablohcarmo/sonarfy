# Avaliar Álbum a partir de uma pesquisa

Status: proposta pendente de implementação e validação. Este guia define a
integração futura; não autoriza nem registra alterações no frontend.
O [contrato HTTP](../api/album-search.md) é a referência para campos e erros.

O usuário logado clica em Avaliar Álbum no Feed e informa nome de álbum ou
artista. Essa ação deve abrir a seleção por pesquisa sem exigir Ecos ou conexão
Spotify. Após selecionar o resultado, abre o editor de avaliação existente.
O formato visual da seleção não é definido neste contrato de backend.

## Sequência e estados

1. Solicitar `GET /api/albums/search?q=...&limit=10&offset=0` com Bearer Sonarfy.
   Aguardar ao menos dois caracteres após trim; recomenda-se debounce de 400 ms.
2. Exibir capa quando presente, título, artista e data/faixas quando disponíveis.
   Usar `spotifyId` como chave e oferecer mais resultados com `nextOffset`.
3. Descartar respostas de consultas anteriores se o texto ou a sessão mudar.
   Alterar o texto limpa a seleção e reinicia a paginação.
4. Ao selecionar, consultar detalhes do álbum e então a avaliação própria.
   Se já existir, abrir edição; caso contrário, abrir criação.
5. Publicar pelo contrato existente. Em 409 de criação, recarregar a avaliação
   própria antes de oferecer edição; não sobrescrever automaticamente.
6. Após publicar/editar/excluir, invalidar as leituras afetadas de álbum, feed,
   perfil e coleção, conforme os guias já existentes.

Separar estados inicial, carregando, sem resultados, falha e seleção pronta.
Não renderizar texto externo como HTML. Se a consulta de detalhes falhar, não
publicar usando apenas os metadados de prévia da busca. Preservar o rascunho
durante falhas de publicação sem aplicá-lo a outro álbum ou outra conta.

401 encerra o fluxo de sessão; 400 permite corrigir a consulta; 502/503/504
permitem informar falha do catálogo sem iniciar OAuth pessoal. Respeitar
`Retry-After` quando houver e evitar retry automático em loop.

As credenciais Spotify pertencem ao backend. O navegador usa somente Bearer
Sonarfy, `cache: "no-store"` e `credentials: "omit"` nessas rotas. O cliente
não fornece `userId`, token Spotify ou credenciais da aplicação.

Nenhuma rota de busca está disponível até implementação e deploy. Não usar
sincronização pessoal como substituto ou resultados de demonstração como dados reais.
