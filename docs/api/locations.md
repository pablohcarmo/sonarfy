# Localização por cidade selecionada

O backend usa o GeoNames para pesquisar e validar cidades. País e cidade são
obtidos do identificador selecionado, não de textos livres enviados pelo cliente.

## Pesquisa pública

`GET /api/locations/cities?q=Campinas`, sem autenticação, retorna 200 com até dez
opções. A consulta é obrigatória, tem de 2 a 100 caracteres após remover espaços
das extremidades e pesquisa por prefixo, por população e na classe `P` do GeoNames.
Os nomes são solicitados em português (`lang=pt`).

```json
[
  {"cityId": 3467865, "name": "Campinas", "region": "São Paulo", "country": "Brasil"}
]
```

O exemplo é ilustrativo. Consumir os identificadores efetivamente retornados.
Cidades homônimas têm identificadores diferentes; exibir região e país para a
seleção. Nenhum resultado é representado por `[]`. Consulta inválida retorna 400.
A busca aceita nomes Unicode; o limite de dez resultados não é uma paginação.

## Cadastro e edição

`cityId` deve ser um inteiro JSON positivo no intervalo de `Long`; texto, decimais,
booleanos e números fora desse intervalo retornam 400, sem coerção.

`POST /api/auth/register` exige `cityId` positivo, além de nome, sobrenome, handle,
e-mail, senha e nascimento. O backend consulta `/getJSON` e aceita apenas uma
localidade da classe `P` com identificador correspondente. Sucesso continua 201.

`PUT /api/users/me` exige Bearer access e os campos `name`, `surname`, `handle`
e `birthDate`. Enviar `cityId` positivo para trocar a localização. Omissão ou
`null` preservam a cidade e o país atuais, sem consultar o provedor. Sucesso
continua 200 com o `UserDto` existente.

```json
{
  "name": "Maria",
  "surname": "Silva",
  "handle": "maria.silva",
  "birthDate": "2000-05-15",
  "cityId": 3467865
}
```

`city` e `country` continuam presentes nas respostas, inclusive no GET, mas não
fazem parte dos DTOs de entrada. Se enviados, são ignorados. Não é possível mudar
o país sem selecionar uma cidade. Com `cityId`, ambos os valores são derivados
do GeoNames; sem ele, nenhum dos dois é alterado. Edição mantém ID, credenciais
e sessões. Nomes derivados precisam ser não vazios e ter até 100 caracteres.

Essa é uma mudança de contrato para o cadastro: clientes que enviam somente
`city`/`country` passam a receber 400 por falta de `cityId`. No PUT, textos legados
não mudam mais a localização. O consumidor deve buscar opções e enviar o ID.
Nenhum arquivo do frontend foi alterado nesta entrega.

## Erros e atomicidade

- 400: consulta inválida, ID ausente no cadastro, não positivo, inexistente ou
  correspondente a um recurso que não seja uma cidade da classe `P`.
- 401: edição sem access válido; a pesquisa é pública. Cabeçalho Bearer inválido
  continua sujeito ao filtro de autenticação existente, inclusive na busca.
- 503: serviço não configurado, falha HTTP, timeout, quota/autorização do provedor,
  JSON inválido, estrutura incompatível ou ID divergente na resposta.
- 409 e os demais erros de dados cadastrais mantêm seus contratos próprios.

Não há formato JSON de erro adicional estável. Nenhuma falha de validação ou do
provedor cria conta ou persiste parcialmente a edição. Não há país padrão nem
reaproveitamento de resultado de outra cidade em caso de falha.

Ver também [cadastro](registration.md), [edição](users-and-profile.md) e
[configuração e evidências](../integrations/locations.md).

Coleções importáveis: [pesquisa e cadastro](sonarfy-locations.postman_collection.json)
e [consulta/edição](sonarfy-users.postman_collection.json). Executar requests
individualmente sobre contas de teste; o cadastro pode enviar e-mail e o PUT altera dados.
