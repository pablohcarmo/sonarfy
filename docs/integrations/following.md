# Integração de relacionamento

Contrato: [seguir/deixar de seguir](../api/following.md).
Coleção importável: [Sonarfy Following](../api/sonarfy-following.postman_collection.json).

O frontend consultado usa js/profile-data.js (adapter de demonstração),
js/profile-state.js e js/profile-views.js. Seu adapter retorna isFollowing e
followers simulados. Esta entrega não altera esses arquivos nem integra o botão.
Na integração futura, use identity.id como targetId e escolha PUT/DELETE pela
intenção. Um 204 confirma a intenção naquele instante; não contém contadores.
A leitura de viewer.isFollowing e contadores reais será feita em entrega posterior.

## Roteiro manual no Postman

1. Importe a coleção e configure baseUrl de ambiente de teste, accessToken local
   de uma conta A ativa/verificada e targetId de uma conta B ativa/verificada.
   Não exporte credenciais nem execute contra contas reais.
2. Execute PUT duas vezes: espere 204 e corpo vazio. No banco de teste, confirme
   somente uma linha (A,B) e follow_date inalterada.
3. Com conta C, siga B; com B, siga A. Volte ao token A e execute DELETE duas vezes:
   espere 204, ausência de (A,B), preservando (C,B) e (B,A).
4. Use o ID de A como alvo: PUT e DELETE retornam 400. Use um ID inexistente: 404.
5. Remova o Bearer, use um token inválido/expirado e um token de cadastro: 401.
6. Apenas no banco descartável, desative A ou B e repita PUT/DELETE: espere
   401 para A ou 404 para B e nenhuma alteração das relações. Repita com verified=false.
   Restaure a elegibilidade no banco de teste. Relações preservadas continuam presentes;
   relações removidas não são recriadas.
7. Para concorrência e mudança de estado entre autenticação e serviço, execute os
   testes PostgreSQL documentados no contrato. O roteiro sequencial não prova corridas.

Não existe endpoint de desativação. Não execute alterações de active/verified no
banco da aplicação. Postman manual, CI remoto e integração do frontend são evidências
separadas dos testes automatizados locais.
## Evidências desta entrega

- Suíte completa local com PostgreSQL habilitado: 722 testes, zero falhas/erros,
  65 ignorados, conforme relatórios Surefire.
- Postman manual executado pelo responsável com contas de teste: PUT/DELETE e
  repetições com 204; persistência sem duplicidade e remoção confirmadas por SQL;
  auto-follow com 400; alvo inexistente com 404; ausência de autenticação, token
  inválido e token registration com 401; exclusão preservando a direção inversa.
- Elegibilidade e concorrência: evidência automatizada no PostgreSQL descartável.
- CI remoto não verificado neste registro.