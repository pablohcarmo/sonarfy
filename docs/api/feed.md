# Feed único de atividades

Decisões funcionais confirmadas pelo usuário em 24/11/2026. Contrato HTTP
confirmado antes do código: leitura JDBC em REPEATABLE READ, sem nova entidade
ou dependência externa. Cursor HMAC com domínio feed-v1, versão e vínculo ao
visitante. Alterar limit entre páginas é permitido. Parâmetros desconhecidos
são ignorados, conforme as rotas atuais; não selecionam abas ou filtros.

## Decisões funcionais aprovadas

- Um único feed, sem abas Para você, Seguindo ou Seguidores.
- Conteúdo limitado às avaliações do próprio visitante e dos perfis que ele segue.
  Ser seguido por alguém não inclui automaticamente as avaliações dessa pessoa.
- Toda avaliação aparece, com ou sem resenha escrita. Comentários realizados
  poderão gerar atividades futuramente, mas não nesta versão.
- Seguir alguém inclui também suas avaliações anteriores ao seguimento,
  respeitando a ordem global. O ato de seguir não gera publicação.
- Deixar de seguir remove esse autor das próximas consultas. As próprias
  avaliações permanecem incluídas sem exigir relacionamento consigo mesmo.
- Ordem decrescente por ID da avaliação, conforme a convenção atual de publicação;
  não ordenar por edição ou prometer ordem de commit das transações.
- Editar nota ou texto atualiza o conteúdo sem voltar ao topo. Excluir remove a
  publicação; excluir e avaliar novamente cria uma publicação com novo ID.
- Acesso somente por conta ativa e verificada, com Bearer. Autores também devem
  estar ativos e verificados. Nenhuma conexão Spotify é necessária.
- Página padrão de 20 itens, máximo 50, com cursor opaco. Novas publicações são
  carregadas ao atualizar o início, sem inserção automática durante a navegação.
- Notificações, likes, comentários e respostas ficam fora desta versão, inclusive
  ações e contadores presentes na referência visual. E-mails transacionais de
  conta e segurança não são afetados por essa exclusão.

## Contrato HTTP

`GET /api/feed?limit=20&cursor=<token>`

Sem parâmetro de aba, perfil ou ordenação; o visitante vem exclusivamente do
Bearer. `limit` aceita inteiros de 1 a 50 e tem padrão 20. Omitir cursor no início;
reenviar nextCursor intacto. `nextCursor: null` encerra a navegação.

Resposta 200, com `Cache-Control: no-store`:

```json
{
  "items": [
    {
      "author": {
        "id": 7,
        "handle": "marina_som",
        "displayName": "Marina Costa",
        "avatarUrl": null
      },
      "reviewId": 42,
      "album": {
        "spotifyId": "album-id",
        "name": "Solar Vibes",
        "coverUrl": null,
        "artist": {"spotifyId": "artist-id", "name": "Tropical Groove"}
      },
      "rating": 8.5,
      "reviewText": "Uma resenha opcional.",
      "createdAt": "2026-11-24T12:00:00Z",
      "updatedAt": "2026-11-24T12:00:00Z"
    }
  ],
  "followingCount": 3,
  "nextCursor": null
}
```

Cada item reutiliza os campos públicos de [ReviewDto](reviews.md), acrescentando
o autor público por item. Não retornar entidade JPA, e-mail, localização, dados
cadastrais ou campos sociais fictícios. `followingCount` conta os perfis seguidos
ativos e verificados; não inclui o próprio visitante e não é total de publicações.
Não há contagem total de publicações nem filtros nesta primeira versão.

O cursor usa keyset por reviewId, com assinatura, versão, vínculo ao
visitante e domínio específico do feed. Rejeita token adulterado, vazio,
incompatível com outra consulta ou pertencente a outro visitante. Toda página
recalcula a autorização de leitura; o cursor não concede acesso a autores.

Erros: 400 para limit/cursor inválido; 401 para Bearer inválido ou
visitante inexistente, inativo ou não verificado. Feed vazio é 200 com items
vazio e nextCursor null. Corpos de erro seguem o mecanismo existente da API.

## Concorrência e persistência

Reutilizar Reviews, Albums, Artists, Users e User_Following. Não criar cópia de
avaliação, entidade de atividade, outbox ou histórico apenas para essa leitura.
Editar e excluir continuam nas rotas existentes, exclusivamente pelo autor.
Não acionar Spotify, sync de Ecos, e-mail ou publicação de eventos sociais.

A implementação mantém elegibilidade, relações, autores, itens e
followingCount consistentes dentro da requisição, com snapshot REPEATABLE READ
iniciado pela releitura do visitante. O principal é lido antes, pelo filtro
Bearer; o serviço relê a elegibilidade para rejeitar um principal inelegível.
Mudanças confirmadas após o início do snapshot afetam a próxima requisição.
Não há snapshot entre páginas. IDs maiores que a fronteira exigem atualizar o
início; IDs menores confirmados tardiamente podem entrar nas próximas páginas.
IDs não prometem ordem de commit. Exclusões removem itens; edições não os deslocam.
Seguir, deixar de seguir e alterações na elegibilidade podem incluir ou remover
conteúdo nas próximas páginas. Avaliações históricas recém-incluídas antes da
fronteira só serão vistas ao reiniciar. Atualizar o início reconcilia a lista.

Ordenação e limite no banco, lendo no máximo limit+1 itens, com joins sem N+1.
Avaliar os índices existentes e o plano real antes de propor índices adicionais;
não afirmar ganhos quantitativos sem benchmark. A migração 015 da Coleção é
uma pendência separada, não autorização para alterar o banco da aplicação.

## Critérios de validação

- Testar avaliações próprias e seguidas, sem incluir autores não seguidos.
- Cobrir histórico anterior ao seguimento, direção da relação, unfollow e feed vazio.
- Validar elegibilidade do visitante e dos autores, campos públicos e ausência
  de dependência de Spotify.
- Cobrir notas zero/dez, texto opcional, ordenação global e navegação completa.
- Verificar limites, cursores inválidos e isolamento por visitante/consulta.
- Cobrir criação, edição, exclusão e mudanças de relações durante a navegação,
  snapshot por requisição e preservação das permissões de escrita.
- Executar PostgreSQL descartável, segurança HTTP real, coleção Postman e suíte
  completa; registrar CI remota separadamente. Resultados desta entrega registrados abaixo.

## Persistência e plano de consulta

Não há migração nova para o feed. A consulta usa EXISTS na PK de User_Following
(follower_id, following_id), sem multiplicar Reviews. A PK reviews(review_id)
permite varredura reversa por ID e a 014 (user_id, review_id DESC) é o índice
existente por autor. A 015 por nota pertence à Coleção e não é requisito do feed;
nenhuma migração foi aplicada ao banco da aplicação.

Foram executados EXPLAIN (ANALYZE, BUFFERS) para primeira página, página com
fronteira e followingCount no PostgreSQL 17.9 descartável, após ANALYZE.
Com a fixture pequena (3 contas, 6 avaliações restantes, 1 relação), o planejador
preferiu sequenciais, joins e sort pequeno; não usou índices para essas leituras.
Isso confirma execução e ordenação/limite no banco, não ganho de performance.
A consulta de limit+1 limita os itens retornados, não a quantidade de linhas que
o planejador precisa examinar. Não há benchmark representativo de volume,
seletividade ou latência; não foi adicionado índice preventivo. Antes de escalar,
medir especialmente visitantes com poucos seguidos numa base com muitas avaliações.
Planos locais: target/feed-query-plans.log.

## Validação reproduzível

Usar exclusivamente PostgreSQL descartável local e banco vazio
`sonarfy_reviews_validation`; importar db/tables.sql com ON_ERROR_STOP=1.
A suíte limpa fixtures e recusa nomes/hosts fora da configuração dedicada:

```powershell
$env:SONARFY_REVIEWS_TEST_JDBC_URL = 'jdbc:postgresql://127.0.0.1:55465/sonarfy_reviews_validation'
$env:SONARFY_TEST_DB_USER = 'postgres'
$env:SONARFY_TEST_DB_PASSWORD = '<senha-exclusiva-do-banco-descartavel>'
.\mvnw.cmd test '-Dtest=FeedPostgresTest,ReviewPostgresTest' '-Dsonarfy.test.reviews-postgres=true'
.\mvnw.cmd test '-Dsonarfy.test.reviews-postgres=true'
```

Coleção somente leitura: [Sonarfy Feed](sonarfy-feed.postman_collection.json).
Configurar baseUrl e accessToken localmente, com conta de teste elegível e
fixtures estáveis. Começar a execução pelo início da coleção; feedQuery vazio.
A requisição Percorrer feed repete até nextCursor=null, validando chaves públicas,
nota, datas, ordem e ausência de duplicatas. Não versionar o token nem usar
contas pessoais em cenários de mutação. O exemplo JSON é v2.1, seguindo as outras
coleções locais; o lint 1.85.0 ignora esse formato (zero arquivos analisados).
Parser JSON e execução contra servidor real são as evidências dessa coleção.
O workflow existente passa a incluir FeedPostgresTest na etapa dedicada a avaliações;
essa configuração não representa execução ou aprovação da CI remota.

## Evidência local — 24/11/2026

Branch feat/activity-feed iniciada em d2c0316 (merge do PR #87), preservando a
documentação local preparada. Nenhum processo Git/Maven concorrente foi encontrado.

- Baseline, imediatamente após criar a branch: `.\mvnw.cmd test`, BUILD SUCCESS,
  835 reportados, 718 executados, 117 ignorados, zero falhas/erros.
- Testes focados com a flag PostgreSQL e `-Dtest=FeedPostgresTest,ReviewPostgresTest`:
  20 executados, zero falhas/erros, BUILD SUCCESS. Incluem segurança Bearer real
  via MockMvc, regras de escrita existentes e alterações concorrentes controladas
  após iniciar o snapshot (elegibilidade de visitante/autor e unfollow).
- Suíte completa com `-Dsonarfy.test.reviews-postgres=true`: 841 reportados,
  739 executados, 102 ignorados, zero falhas/erros, BUILD SUCCESS. Inclui o teste
  unitário adicional de assinatura, versão e fronteira do cursor.
- PostgreSQL 17.9 exclusivo em 127.0.0.1:55465, bancos novos
  sonarfy_reviews_validation e sonarfy_feed_http_validation, schema consolidado
  importado com ON_ERROR_STOP e Hibernate validate. Nenhuma migração nova;
  aplicação/reaplicação de migração não se aplica a esta mudança.
- Servidor real iniciado pelas classes compiladas na porta 18090, com configuração
  isolada, três contas sintéticas e login pelo endpoint real. Nenhuma conexão
  Spotify ou integração externa usada. Coleção executada com
  `postman collection run <copia-local-com-token> --no-report-events
  --timeout-request 10000 --timeout-script 10000`: duas rodadas, 16 requisições,
  31 asserções e zero falhas, exit 0. A segunda percorreu três páginas de histórico.
  Cópia temporária com token e configuração do servidor removidas ao terminar.
- Mais 20 checks HTTP reais: login, avaliações próprias, seguir/desseguir,
  isolamento de cursor, edição sem reposicionamento, exclusão da fronteira,
  elegibilidade, Bearer inválido e tentativa de escrita por terceiro rejeitada.
- JSON analisado e `git diff --check` aprovado. Lint Postman: zero arquivos
  analisados, portanto não contado como validação estrutural. CI remota não
  executada; frontend não alterado nem testado. Integração frontend e benchmark
  representativo permanecem pendentes; nenhum incremento funcional backend restante.

Logs locais: target/feed-branch-tests.log, feed-postgres-tests.log,
feed-full-tests.log, feed-postman.log, feed-postman-followed.log e
feed-http-result.json, além dos planos citados acima. Servidor HTTP e cluster
PostgreSQL descartável encerrados ao concluir a validação.

## Confirmação do usuário — 24/11/2026

Após a entrega do backend e do relatório de validação, o usuário confirmou:
“Tudo certo, atualize a documentação”. O registro representa aceite da entrega
relatado pelo usuário, sem especificação de cenários manuais, ambiente ou
respostas HTTP adicionais. Não acrescenta evidência de CI remota nem confirma
integração ou validação visual do frontend. Permanecem as limitações e os
resultados técnicos registrados acima.

## Conferência da integração frontend — 24/11/2026

Inspeção em leitura da branch feat/feed-integration, commit `515aa1e`, confirmou
adapter HTTP padrão para GET /api/feed e GET /api/profile/me, lista única,
paginação opaca, rating/2, estados de erro e invalidação por sessão e mutações.
Fixtures são explícitas. O contrato HTTP permanece compatível, sem novos campos.
Os cards mantêm curtir/comentar indisponíveis, sem requisições ou contadores;
perfil e Spotify são destinos reais, sem rota fictícia de álbum/avaliação completa.

Esta conferência substitui o status anterior de integração frontend pendente:
o código consumidor está implementado. O [guia de integração](../integrations/feed.md)
registra o comportamento confirmado e as evidências relatadas pela documentação
frontend. Testes de navegador usaram respostas interceptadas e não foram repetidos
aqui. Comunicação com backend real, CI remota, merge e implantação não foram
verificados nesta etapa; os resultados anteriores permanecem como histórico.
