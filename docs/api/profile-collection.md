# Coleção do perfil

Contrato definido antes da implementação. A coleção é uma consulta de Reviews,
Albums, Artists e Users; não cria entidade, cópia de metadados ou avaliação.

Notificações da plataforma, likes, comentários e respostas são funcionalidades
futuras, fora desta versão e dos seus critérios de conclusão. Não fazem parte
deste contrato nem são dependências da Coleção ou do feed.

`GET /api/profile/by-handle/{handle}/collection`

Exige Bearer de conta ativa e verificada. Handle sem `@`, comparação sem distinguir
maiúsculas. Funciona para o próprio perfil e terceiros, sem seguir o autor ou
conectar Spotify. Não chama Spotify, sincronização ou e-mail. `Cache-Control: no-store`.

## Parâmetros e resposta

- `limit`: inteiro 1–50, padrão 20.
- `sort`: `recent` (padrão), `oldest`, `highest`, `lowest`, sensível a maiúsculas.
- `writtenOnly`: `true` ou `false` (padrão), separado da ordenação. Seleciona
  `review_text IS NOT NULL AND btrim(review_text) <> ''`. A escrita normaliza
  espaços Unicode e converte texto vazio em null.
- `cursor`: token opaco assinado retornado em `nextCursor`; omitir no início.
  Vazio, adulterado ou de outro contexto resulta em 400. `beforeId` não se aplica.

```json
{
  "author": {"id":7,"handle":"musicfan","displayName":"Music Fan","avatarUrl":null},
  "items": [],
  "totalReviews": 12,
  "matchingReviews": 4,
  "nextCursor": null
}
```

`items` usa exatamente [ReviewDto](reviews.md): reviewId, album.spotifyId/name/
coverUrl/artist.spotifyId/name, rating (0–10, passo 0,5), reviewText opcional,
createdAt e updatedAt. Nenhuma entidade JPA ou dado privado é exposto.
`totalReviews` conta todas as avaliações existentes do autor (um álbum por avaliação);
`matchingReviews` conta as que atendem ao filtro, antes da fronteira do cursor.
Sem filtro, ambos são iguais. Contagens não representam o tamanho da página.
Perfil vazio retorna 200, totais zero e nextCursor null.

`recent` ordena reviewId DESC e `oldest` reviewId ASC, conforme a convenção de
publicação já usada em avaliações: atribuição do ID, não createdAt, updatedAt ou
ordem de commit. `highest` ordena rating DESC, reviewId DESC; `lowest` ordena
rating ASC, reviewId ASC. O desempate é imutável; inverter ambas as direções
permite atender as duas ordenações por nota com um único índice. Ordenação global no banco,
com keyset e leitura de no máximo limit+1 itens; nunca ordenação local da página.

O cursor contém versão e fronteira (nota e ID quando necessário), autenticada
por HMAC, vinculada ao autor por ID, visitante, ordenação e filtro. Não concede
acesso e não depende de a avaliação da fronteira continuar existindo. Alterar
limit é permitido. Troca de segredo invalida cursores. O cliente não deve decodificá-los.

## Erros e concorrência

- 400: limit, sort, writtenOnly ou cursor inválido/incompatível.
- 401: Bearer ausente/inválido ou visitante inexistente, inativo ou não verificado.
- 404: indistinguível para autor inexistente, inativo ou não verificado.

Corpos de erro seguem Spring/Security, sem envelope estável novo.
Elegibilidade de visitante/autor, identidade, itens e contagens são lidos no mesmo
snapshot REPEATABLE READ por requisição. Alteração de elegibilidade confirmada
depois do início do snapshot afeta a próxima consulta. Não há snapshot entre páginas.

Sem mudanças, navegação completa não repete nem omite itens. Inserções aparecem
se estiverem depois da fronteira na ordem escolhida; antes dela exigem reinício.
Exclusões removem itens e podem encurtar a navegação, inclusive na fronteira.
Editar nota pode mover um item por cima da fronteira nas ordenações por nota,
causando repetição ou omissão entre requisições. Alterar texto pode mudar a inclusão
no filtro. Contagens refletem cada requisição, podendo variar. Reiniciar para
reconciliar mudanças. Não há expiração/snapshot persistido do cursor.

As rotas atuais `/reviews`, `/reviews/me`, beforeId e nextBeforeId permanecem
inalteradas. A rota específica evita dar dois significados ao cursor numérico e
mantém o custo das contagens fora das consultas atuais. PUT/DELETE continuam
restritos ao autor pelas rotas existentes.

## Persistência

Aplicar `015_add_collection_rating_index.sql` após 014 em bancos existentes,
mediante autorização do responsável. O schema consolidado já inclui o índice
para bancos novos. Não importar o schema completo em banco existente.
O índice (user_id, rating DESC, review_id DESC) atende filtro por autor e fronteira
composta, também por varredura reversa. 014 atende ambas as ordens por ID.
Não há índice parcial preventivo para texto: o filtro é aplicado durante a
varredura. Contagens exatas percorrem as avaliações do autor; não são constantes
nem um benchmark de latência. CREATE INDEX pode bloquear gravações; planejar janela
para tabelas grandes. IF NOT EXISTS permite reaplicar sem duplicar o índice.

## Validação local — 23/11/2026

Branch `feat/profile-collection`, iniciada sobre `67bddf0`, merge do PR #86,
com árvore inicialmente limpa. Nenhum processo de testes/Git de outro agente
estava em execução; os processos Java preexistentes eram auxiliares do IDE.

- `.\mvnw.cmd test`, imediatamente após criar/trocar a branch: 831 reportados,
  718 executados, 113 ignorados, zero falhas/erros.
- `.\mvnw.cmd test -Dtest=ReviewPostgresTest -Dsonarfy.test.reviews-postgres=true`:
  15 executados no PostgreSQL 17.9 descartável, zero falhas/erros. Inclui as
  rotas anteriores e segurança real pelo MockMvc, sem simular autenticação.
- `.\mvnw.cmd test -Dsonarfy.test.reviews-postgres=true`: suíte completa com
  835 reportados, 733 executados, 102 ignorados, zero falhas/erros.
- `postman collection run target/collection-runtime.postman_collection.json
  --no-report-events --timeout-request 10000 --timeout-script 10000`: exit 0,
  26 requisições e 90 asserções contra servidor HTTP real em 127.0.0.1:18089,
  PostgreSQL descartável e login de contas exclusivas de teste. O arquivo temporário
  só adicionou variáveis locais à coleção versionada; foi removido ao terminar.
  Outros 16 checks HTTP cobriram login, perfil próprio/vazio, isolamento do cursor,
  mutações por terceiros, autor/visitante inelegíveis e Bearer inválido.
- Importação de `db/tables.sql` em bancos vazios e aplicação/reaplicação da 015
  com `psql -v ON_ERROR_STOP=1`: aprovadas, preservando as avaliações.
- `git diff --check`: aprovado. JSON validado por parser e pela execução real.
  O lint do CLI 1.85.0 reporta zero arquivos para a coleção JSON v2.1, portanto
  esse resultado não é evidência de validação estrutural pelo lint.

Para reproduzir PostgreSQL, seguir [avaliações](reviews.md#validação-reproduzível),
usando exclusivamente `sonarfy_reviews_validation` em localhost e as variáveis
SONARFY_REVIEWS_TEST_JDBC_URL, SONARFY_TEST_DB_USER e SONARFY_TEST_DB_PASSWORD.
A suíte apaga fixtures desse banco dedicado, nunca usar dados pessoais.
A coleção [Postman reutilizável](sonarfy-collection.postman_collection.json)
é somente leitura: configurar baseUrl, accessToken e profileHandle localmente,
com perfil elegível e fixtures estáveis. Credenciais não são versionadas.
Ela percorre todas as páginas de cada combinação de ordenação/filtro.

Logs locais em target/collection-baseline.log, collection-postgres-tests.log,
collection-full-tests.log, collection-postman.log e collection-migration-validation.log;
resumo HTTP em target/collection-http-result.json. Integrações externas não foram
acionadas. CI remota, integração visual/frontend e benchmark representativo não
foram executados. Servidor HTTP e PostgreSQL temporários foram encerrados ao final.
