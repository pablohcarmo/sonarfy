# Avaliações no perfil

A seção Coleção usa uma [rota específica](profile-collection.md), com ordenações,
filtro e totais. A rota de avaliações abaixo mantém seu contrato e cursor originais.

`GET /api/profile/by-handle/{handle}/reviews?limit=20&beforeId=123`
exige Bearer de conta ativa e verificada, inclusive para consultar terceiros.
O handle é comparado sem distinguir maiúsculas, como na consulta do perfil;
envie-o sem `@`. Não há chamada ao Spotify nem exigência de seguir o autor.

Resposta `200`, com `Cache-Control: no-store`:

```json
{
  "author":{"id":7,"handle":"musicfan","displayName":"Music Fan","avatarUrl":null},
  "items":[],
  "nextBeforeId":null
}
```

Cada item usa o mesmo [ReviewDto](reviews.md) da coleção própria: `reviewId`,
`album` (com artista), `rating`, `reviewText`, `createdAt` e `updatedAt`.
O autor fica no nível da página, sem e-mail, localização ou dados cadastrais.
Não há contadores sociais, nota agregada, favoritos ou filtros nesta entrega.

`limit` aceita 1–50 (padrão 20). `beforeId` é inteiro positivo de 64 bits.
A ordem é `reviewId DESC`, sem reordenar por nota ou data de edição.
Reenvie `nextBeforeId` como `beforeId`; null encerra a navegação. O cursor
é uma fronteira numérica, não uma permissão, e toda página restringe a consulta
ao autor selecionado. Reinicie a paginação ao trocar de perfil ou de conta.
Não há snapshot entre requisições: publicações novas exigem recarregar o início,
exclusões podem encurtar páginas e alterações aparecem nas leituras seguintes.

- `200` com lista vazia: perfil elegível sem avaliações nessa página.
- `400`: paginação inválida ou parâmetro numérico malformado.
- `401`: visitante sem autenticação válida ou conta inelegível.
- `404`: mesmo resultado para autor inexistente, inativo ou não verificado.

Cada chamada lê elegibilidade, identidade pública e avaliações em um snapshot
transacional. Desativação posterior ao início desse snapshot afeta a próxima
consulta; não apaga avaliações. Reativação torna as avaliações visíveis novamente.
Renomear o handle exige usar o novo endereço. Consultar outro perfil não concede
permissão para editar ou excluir suas avaliações.

O índice `idx_reviews_user_review_desc` atende autor e fronteira de paginação.
Aplicar `014_add_profile_reviews_index.sql` em bancos existentes; o schema
consolidado já o inclui. Não importar o schema completo sobre banco existente.

## Validação local — 23/11/2026

`ReviewPostgresTest` executou 11 testes com PostgreSQL 17 descartável e MockMvc
pela cadeia real de segurança, sem chamadas ao Spotify. Nenhuma falha ou erro.
Inclui leitura entre contas, dados públicos do autor, perfis vazios/inelegíveis,
reativação, troca de handle, paginação com empates e edições, validação de
parâmetros e rejeição de mutação por terceiros. A suíte também preserva os
cenários anteriores de avaliações próprias e integração com Ecos.

Suíte completa: `.\mvnw.cmd test -Dsonarfy.test.reviews-postgres=true`, com
as variáveis do banco dedicado descritas em [avaliações](reviews.md#validação-reproduzível):
831 reportados, 729 executados, 102 ignorados, zero falhas/erros (`BUILD SUCCESS`).
O comando padrão executado imediatamente após criar a branch também passou:
828 reportados, 718 executados, 110 ignorados, zero falhas/erros.

O schema consolidado foi importado em banco vazio. No mesmo banco descartável,
uma transação removeu o índice, aplicou a migration `014` duas vezes e conferiu
sua definição e a igualdade das linhas de Reviews antes/depois. Todas as
verificações passaram, com rollback ao final. O servidor temporário foi encerrado.

A coleção Postman foi atualizada e validada como JSON. A execução HTTP posterior
está registrada abaixo. Não há validação visual do frontend ou benchmark com
volume representativo. A aprovação da CI remota ainda não foi verificada.

## Postman com servidor HTTP real — 23/11/2026

Após autorização do usuário, a migration `014` foi aplicada no banco local
`localhost:5433/sonarfy`, com backup e preservação dos dados conforme
[persistência](../persistence.md#aplicação-local-da-migration-014-em-23112026).
O código compilado da branch foi iniciado temporariamente em
`http://127.0.0.1:18087`, usando esse banco, segurança real e login de duas contas
temporárias. Integrações externas não foram acionadas; não houve acesso ao Spotify.

O Postman CLI `1.85.0` executou a coleção versionada de avaliações com variáveis
locais, precedida por login e complementada com cenários de perfil. Credenciais
foram encadeadas em memória pelos scripts, sem impressão ou publicação dos resultados.
Foram quatro execuções de `postman collection run ... --no-report-events`:

| Cenário | Requisições | Asserções | Resultado |
| --- | --- | --- | --- |
| Ciclo próprio e perfil entre contas | 33 | 48 | exit 0 |
| Perfil temporário inativo | 2 | 3 | exit 0 |
| Perfil temporário não verificado | 2 | 3 | exit 0 |
| Restauração de elegibilidade | 2 | 4 | exit 0 |

Total: 39 requisições, 58 asserções, zero falhas. Inclui paginação com `limit=1`,
ausência de duplicatas, fim da coleção, dados públicos do autor, criação/edição/
exclusão refletidas no perfil, rejeição de alteração por terceiro, `401` sem
Bearer, `404` para perfil inexistente/inelegível e `400` para parâmetros inválidos.

O backend temporário foi encerrado; fixtures e arquivos transitórios de configuração
e coleção foram removidos. As linhas anteriores das 18 tabelas foram conferidas
por contagens/fingerprints e preservadas. Sequências de identidade avançaram
durante a criação das fixtures e não foram retrocedidas. Logs e resumo locais:
`target/profile-reviews-postman.log`, `target/profile-reviews-postman-result.json`
e `target/profile-reviews-migration-result.json`. O frontend não foi alterado.

## Confirmação posterior da aplicação

Após encaminhar a integração ao especialista em frontend, o usuário confirmou
“Testes feitos, tudo conforme.” O relato de validação da aplicação está registrado
no [guia de integração](../integrations/reviews.md#confirmação-de-integração-pelo-usuário).
Essa evidência é fornecida pelo usuário; este agente não executou testes visuais
nem recebeu detalhamento dos cenários do frontend. A CI remota permanece sem
verificação nesta entrega.
