# Integração da seleção de localização

Contrato: [localização](../api/locations.md). Selecionar cidade por ID impede
combinações arbitrárias de cidade e país e distingue cidades homônimas.

## Configuração

Definir `GEONAMES_USERNAME` no ambiente do backend com uma conta GeoNames habilitada
para web services. Também é aceito `geonames.username` por configuração externa.
O username do frontend não é carregado automaticamente pelo backend. Não usar a
conta de demonstração nem versionar configuração pessoal.

`geonames.base-url` tem padrão `https://secure.geonames.org`. Sobrescrever somente
em configuração controlada, por exemplo para a simulação local. Sem username,
pesquisa e seleção retornam 503; editar outros campos sem `cityId` continua funcionando.
Não foi alterado o `application.properties` local.

O cliente limita conexão e leitura a três segundos cada e não repete requisições
automaticamente. Faz GET em `searchJSON` com `name_startsWith`, `maxRows=10`,
`featureClass=P`, `orderby=population`, `lang=pt` e username. A seleção usa
`getJSON` com `geonameId`, `lang=pt` e username. Corpo remoto e URL com configuração
não são propagados nas mensagens de erro do Sonarfy.

## Adaptação futura do consumidor

1. Pesquisar com pelo menos dois caracteres, exibindo cidade, região e país.
2. Guardar o `cityId` da seleção. Se o texto for alterado, invalidar a seleção.
3. Exibir país preenchido pela opção, sem edição direta.
4. No cadastro, enviar `cityId`. Na edição, enviar somente se houver seleção nova;
   omitir para preservar a localização atual. Os outros quatro campos continuam
   obrigatórios no PUT.
5. Em 400, corrigir a seleção; em 503, informar indisponibilidade e permitir nova
   tentativa. Nunca assumir Brasil ou outro país quando a busca falhar.

O cadastro atual consulta GeoNames no navegador e envia textos livres. Essa parte
precisa ser adaptada ao contrato novo; o frontend e o Figma não foram alterados.
Os nomes atuais das contas são preservados. Nenhum `cityId` foi adicionado ao
banco: o ID valida a seleção e os nomes ficam como representação persistida.
O GET não retorna `cityId`; não é preciso redescobri-lo para editar outros campos.

## Simulação e reprodução

Não foi encontrado mock do GeoNames na busca de workspaces do Postman. A consulta
Orbit não retornou os endpoints GeoNames necessários; o contrato foi conferido
na documentação oficial e a simulação foi construída a partir desses formatos.

Arquivos em `test-support/geonames/` e `test-support/location.sim.yaml` representam
apenas `searchJSON` e `getJSON`. As cidades e IDs 1–3 são fixtures sintéticas,
nunca identificadores para uso contra o serviço real. As rotas não cobertas
respondem 501. Não publicar ou executar esses dados como catálogo de produção.

Em um terminal:

```powershell
postman simulation run test-support/location.sim.yaml --port 55441 --no-history --output ndjson
```

Em outro terminal:

```powershell
.\mvnw.cmd '-Dtest=LocationIntegrationTest' '-Dsonarfy.test.location=true' test
```

O teste usa `http://127.0.0.1:55441/geonames`; para outra porta, fornecer
`-Dsonarfy.test.location-url=http://127.0.0.1:PORTA/geonames`. O roteamento é por
caminho, sem proxy, porque o cliente Java recebe a base local explicitamente.
Não são usados cookies, tokens ou credenciais reais na simulação.

Segurança, controllers, serviços, cliente HTTP e JPA são reais. Somente o GeoNames
é substituído pela simulação Postman e o transporte de e-mail é simulado. O banco
é H2 isolado; não fornecer o banco da aplicação aos testes.

## Fontes e limites

- [GeoNames Search](https://www.geonames.org/export/geonames-search.html): filtros,
  nomes localizados e limites da busca.
- [Consulta por geonameId](https://www.geonames.org/export/web-services.html#get):
  seleção por identificador.
- [Erros GeoNames](https://www.geonames.org/export/webservice-exception.html):
  status de recurso inexistente, quota e falhas do provedor.
- Dados do GeoNames exigem atribuição; preservar a referência ao integrar a UI.

A simulação valida o consumidor HTTP e seus resultados; não comprova disponibilidade,
quota ou conteúdo atual do GeoNames real. Não houve validação de navegador,
SMTP real, PostgreSQL, CI remoto ou migrations nesta entrega.

O diff supera o alvo aproximado de 300 linhas por incluir a substituição do DTO
mutável de cadastro por record, integração HTTP, adaptação dos testes existentes,
simulação reproduzível e cobertura da cadeia real. São partes do mesmo contrato:
separar sua aplicação entre cadastro, edição e validação deixaria temporariamente
os fluxos com regras diferentes ou sem cobertura das falhas externas.

## Evidências — 18/11/2026

- Branch originalmente baseada em `main` no commit `90344e8`. Após os merges dos
  PRs #66 e #67, a base foi atualizada para `5e35106`, preservando o contrato de
  localização junto da normalização de handle e da leitura estrita de nascimento.
- Suíte após criar a branch: 578 reportados, zero falhas/erros e 57 ignorados.
- Após adaptar o contrato e os testes existentes, a execução padrão inicial
  reportou 576 testes, zero falhas/erros e 57 ignorados. Dois casos de validação de
  texto livre de cidade/país deixaram de corresponder ao novo contrato e foram
  substituídos pela cobertura específica da localização.
- `LocationIntegrationTest` contra a simulação Postman: 19 testes, zero falhas/erros
  e nenhum ignorado. Verifica busca pública, cidades homônimas, país derivado,
  cadastro sem ID, preservação sem ID, tipos inválidos, overflow, falhas HTTP,
  timeout, quota, JSON inválido, resposta divergente e atomicidade com refresh.
- Suíte completa com simulação (`.\mvnw.cmd '-Dsonarfy.test.location=true' test`):
  595 reportados, zero falhas/erros e 57 ignorados. Foi observado tráfego real do
  cliente para os endpoints servidos pela simulação; nenhuma consulta ao GeoNames
  real foi feita. A simulação foi encerrada ao final.
- Coleções JSON e corpos com placeholders analisados sintaticamente; não houve
  execução da coleção contra o backend pelo Postman CLI. Os testes HTTP usaram
  MockMvc e a cadeia real de segurança, não navegador.
- O teste de localização é opt-in porque exige a simulação em execução. A suíte
  padrão continua usando o cliente GeoNames simulado em memória nos testes antigos.
- Antes da atualização da base, o diff de código, testes, mock e configurações
  executáveis somava 602 linhas alteradas.
  Documentação, coleções JSON e contexto documental da simulação não entram no cálculo.
- Após atualizar a base: suíte padrão com 630 reportados, zero falhas/erros e 65
  ignorados; suíte com simulação de localização habilitada com 641 reportados,
  zero falhas/erros e 57 ignorados. Os exemplos Postman preservam os três fluxos.
- Revisão final antes do push: links locais e coleções JSON conferidos, incluindo
  corpos com placeholders. Suíte completa com simulação repetida: 641 reportados,
  zero falhas/erros e 57 ignorados. O diff final de código, testes, mock e
  configurações executáveis soma 608 linhas alteradas, sem os artefatos documentais.

## Validação manual relatada pelo usuário — 18/11/2026

- Busca real no Postman por `Ribeirao+Pires` retornou opções com identificador,
  nome, região e país. O usuário selecionou Ribeirão Pires, São Paulo, Brasil.
- PUT com o identificador selecionado: 200; GET confirmou a persistência.
- PUT sem `cityId`, enviando textos de outra cidade: 200.
- PUT com `cityId` igual a 0 ou -1: 400.
- PUT sem `cityId`, enviando `city` e `country` como `Fake`: 200.
- GET final confirmou que a localização continuava Ribeirão Pires/Brasil,
  demonstrando que os textos livres foram ignorados e os erros não a alteraram.

Essas verificações reais foram executadas pelo usuário, separadamente dos testes
automatizados com simulação. Cadastro de conta nova com `cityId` ainda não teve
validação manual relatada. Dados pessoais, credenciais e identificadores de conta
foram omitidos deste registro.
