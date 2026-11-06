# Nome de exibição do usuário

Antes de iniciar a aplicação com o novo mapeamento de `User`, execute
`add-user-display-name.sql` no PostgreSQL do ambiente de destino. O projeto usa
`ddl-auto=none`; este script não é executado automaticamente.

Exemplo, com a conexão configurada no cliente PostgreSQL:

```sh
psql -v ON_ERROR_STOP=1 -f docs/database/add-user-display-name.sql
```

O script cria `Users.display_name` e preenche os valores nulos com nome e
sobrenome, limitados a 255 caracteres. Valores já definidos são preservados.
Pode ser reaplicado, mas voltará a preencher qualquer valor nulo existente.
A coluna é opcional nesta etapa; cadastro e edição via DTO ficam fora deste PR.

A alteração é aditiva e compatível com a versão anterior da aplicação.
Em caso de rollback da aplicação, mantenha a coluna para preservar os dados.
