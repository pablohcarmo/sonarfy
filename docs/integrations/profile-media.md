# Mídia de perfil — avatar no Azure Blob Storage

Avatar e wallpaper persistentes. O PUT textual permanece inalterado e continua
ignorando avatar/wallpaper. Frontend e Azure não são modificados automaticamente
pelo backend. O container precisa existir; a aplicação não cria containers nem
altera permissões públicas ao iniciar.

## Configuração de implantação

Fornecer via ambiente ou configuração externa, sem versionar `application.properties`:

```text
APP_AVATAR_ENABLED=true
APP_AVATAR_ENDPOINT=https://stsonarfydev001.blob.core.windows.net
APP_AVATAR_CONTAINER=images
```

Para publicar também a rota de wallpaper, definir `APP_WALLPAPER_ENABLED=true`.
As duas rotas têm flags independentes e usam o mesmo cliente Azure: endpoint,
container, credenciais, timeout e limites de bytes/pixels continuam nas propriedades
`app.avatar.*` por compatibilidade. É possível habilitar somente wallpaper; nesse
caso ainda é obrigatório fornecer `APP_AVATAR_ENDPOINT`. Nenhuma flag é ligada
automaticamente pela presença de credenciais.

Wallpapers têm limites de saída próprios: `app.wallpaper.max-width=1600` e
`app.wallpaper.max-height=600` (variáveis `APP_WALLPAPER_MAXWIDTH` e
`APP_WALLPAPER_MAXHEIGHT`). Não fixam a proporção: cada imagem é reduzida para caber
nessa caixa. Avatar mantém o recorte e saída quadrada existentes.

Para testar wallpaper no Postman, usar POST `/api/profile/me/wallpaper`, Bearer e
form-data `file` do tipo File. Conferir `identity.coverUrl`, GET posterior, leitura
anônima da nova URL e exclusão da capa anterior gerenciada. Confirmar que o avatar
não foi alterado. Imagem 3200×1200 deve resultar em PNG 1600×600; imagem 1200×800
em 900×600. Não é necessário enviar nome/bio ou coordenadas de recorte.

| Propriedade | Padrão | Variável de ambiente |
| --- | --- | --- |
| app.avatar.max-bytes | 5242880 | APP_AVATAR_MAXBYTES |
| app.avatar.max-pixels | 16000000 | APP_AVATAR_MAXPIXELS |
| app.avatar.size | 512 | APP_AVATAR_SIZE |
| app.avatar.timeout-seconds | 10 | APP_AVATAR_TIMEOUTSECONDS |

Os limites são aplicados no processador; bytes e envelope também são limitados
no servlet container. O endpoint deve apontar para uma conta HTTPS de Blob Storage
no Azure público. Defaults não habilitam o recurso sem configuração explícita.

Autenticação usa `DefaultAzureCredential`, conforme a [orientação oficial Java](https://learn.microsoft.com/en-us/azure/storage/blobs/storage-blob-upload-java).
Na VM, habilitar identidade gerenciada e atribuir **Storage Blob Data Contributor**
no escopo do container `images`. Em desenvolvimento, pode ser usada uma sessão
Entra local, por exemplo Azure CLI. Não colocar chaves, connection strings ou tokens
em arquivos versionados nem enviá-los no upload do frontend.

Para leitura pública direta aprovada: a conta precisa permitir acesso anônimo e
`images` deve usar nível **Blob**, sem listagem anônima. Isso expõe a leitura dos
arquivos do container por URL; ele deve conter somente conteúdo destinado a ser
público. A configuração anteriormente fornecida (`allowBlobPublicAccess=false`)
ainda bloqueia essa leitura. Validar as duas configurações no Azure antes de
habilitar a funcionalidade para usuários. [Referência oficial](https://learn.microsoft.com/en-us/azure/storage/blobs/anonymous-read-access-configure).

## Consumidor e validação manual

1. Usar uma conta de teste e JPEG/PNG, quadrado ou retangular. Sem coordenadas, a
   API recorta o quadrado central. Para seleção explícita, enviar cropX/cropY/cropSize
   em pixels após orientação EXIF, conforme o contrato de upload de avatar.
2. POST `/api/profile/me/avatar`, Bearer, Body form-data, chave `file` do tipo File.
   Não definir manualmente o header multipart com boundary incompleto.
3. Em 200, reconciliar a tela com `ProfileDto` e confirmar pelo GET que a URL foi
   persistida; wallpaper e texto devem permanecer iguais.
4. Abrir a URL retornada sem Authorization: confirmar 200, PNG 512×512 e cache
   configurado. Essa etapa verifica leitura pública real, além do upload autenticado.
5. Substituir a imagem: confirmar outra URL e remoção do blob anterior gerenciado.
   A primeira resposta pode permanecer em cache no navegador.
6. Exercitar limites e credenciais inválidas. Em 503, manter a imagem atual e
   permitir nova tentativa explícita; não repetir automaticamente em loop.

Coleção: [sonarfy-profiles.postman_collection.json](../api/sonarfy-profiles.postman_collection.json),
pasta de upload que altera a conta e o armazenamento de teste. Arquivos são
selecionados localmente, sem caminho absoluto compartilhado no JSON.

## Próximas fatias

- Integração visual de wallpaper: referência de 1600×600, com enquadramento
  responsivo no frontend; o backend mantém a proporção e não faz recorte.
- Capas de álbum: URLs de `images[]` do Spotify, selecionadas pelas dimensões
  disponíveis, sem upload pelo usuário ou cópia para Azure nesta entrega.
  Preservar arte completa, sem corte/sobreposição, com atribuição e link ao Spotify,
  conforme [diretrizes oficiais](https://developer.spotify.com/documentation/design).
- Reconciliação de órfãos e remoção explícita de avatar precisam de entregas próprias.

## Evidências locais — 19/11/2026

- Branch `feat/profile-avatar-upload`, baseada na main `9db3b91`. Suíte obrigatória
  após a troca de branch: 683 testes reportados, zero falhas/erros e 65 ignorados.
- Suíte final (`.\mvnw.cmd test`): 708 reportados, zero falhas/erros, 65 ignorados.
  Inclui dois testes de configuração e 23 cenários de integração de avatar.
- `AvatarUploadIntegrationTest` exercita MockMvc pela cadeia real de segurança,
  processador de imagem, transações JPA e cliente Azure SDK real. O provedor é um
  servidor HTTP local em porta aleatória, construído conforme as operações
  [Put Blob](https://learn.microsoft.com/en-us/rest/api/storageservices/put-blob),
  [Get Blob](https://learn.microsoft.com/en-us/rest/api/storageservices/get-blob)
  e [Delete Blob](https://learn.microsoft.com/en-us/rest/api/storageservices/delete-blob).
  Outros métodos retornam 501. Não houve mock publicado nem chamadas ao Azure real.
- Cenários cobrem substituição, leitura anônima no simulador, concorrência, rollback,
  erros 500, timeout, conflitos 409/412, falha de limpeza após commit, formatos,
  limites de bytes/pixels, corrupção, animação, oito orientações EXIF, remoção de
  EXIF e rejeição de autenticação inelegível. Upload usa criação condicional,
  Content-Type PNG e nenhuma repetição automática nos cenários exercitados.
- Os 23 cenários de integração passaram também em PostgreSQL 17.9 descartável,
  banco separado `sonarfy_avatar_test` na porta 55450, schema consolidado importado
  e Hibernate em `validate`. Nenhuma migration foi aplicada no banco da aplicação.
  Para reproduzir após preparar esse banco descartável:
  `.\mvnw.cmd test -Dtest=AvatarUploadIntegrationTest -Dsonarfy.test.avatar-postgres=true`.
  O teste fixa nome/credenciais descartáveis e permite alterar somente a porta com
  `sonarfy.test.avatar-port`, evitando conexão acidental ao banco da aplicação.
- Coleção de 14 requisições analisada como JSON e scripts verificados sintaticamente.
  Ela não foi executada manualmente no Postman nem via runner contra a aplicação.
- A simulação não comprova Managed Identity, RBAC, ACL pública, disponibilidade,
  faturamento ou comportamento completo do Azure. Esses itens exigem validação
  na conta real, pelo roteiro acima. Não houve alterações no Azure, frontend ou
  `application.properties`; CI remoto não executado nesta etapa.

## Evolução do recorte

O backend agora aceita retângulos em retrato ou paisagem e recorta o quadrado central
quando cropX/cropY/cropSize são omitidos. A seleção explícita usa pixels após EXIF;
regras de tipo, bytes e pixels originais permanecem iguais. A interface de seleção
do recorte no frontend permanece pendente.

Suíte completa após a evolução: 708 testes reportados, zero falhas/erros, 65 ignorados.
Os testes verificam recorte central, seleção explícita, retrato/paisagem, coordenadas
parciais e fora dos limites, overflow e as oito orientações EXIF. Essa evolução foi
validada com H2 e provedor HTTP simulado; não foi reexecutada no PostgreSQL ou Azure real.

Antes da evolução, o usuário confirmou upload PNG/JPEG no Azure real, URL persistida,
leitura em janela anônima e exclusão do blob anterior após substituição. A primeira
tentativa retangular foi rejeitada pela política anterior; não é o comportamento
atual. Sem execução remota de CI ou publicação de PR nesta etapa.

Após reiniciar a versão com recorte, o usuário confirmou manualmente o upload de
imagem retangular sem coordenadas: a imagem foi recortada em quadrado, localizada
no armazenamento Azure e sua nova URL retornada em ProfileDto, com os demais dados
do perfil preservados. Isso valida o recorte central com o provedor real; seleção
explícita por coordenadas permanece coberta pelos testes automatizados, sem
confirmação manual no Azure nesta etapa. Não foram registrados dados pessoais ou
credenciais na evidência.

## Entrega de wallpaper — 19/11/2026

- PR de avatar #73 confirmado integrado na main `b212f8e`, com Java CI aprovado.
  Suíte local da main e suíte após criar `feat/profile-wallpaper-upload`: ambas
  reportaram 708 testes, zero falhas/erros e 65 ignorados.
- Suíte completa final (`.\mvnw.cmd test`): 715 testes reportados, zero falhas/erros,
  65 ignorados. Inclui a regressão de avatar e o fluxo novo de wallpaper.
- Os 30 cenários de `AvatarUploadIntegrationTest` passaram também em PostgreSQL
  17.9 descartável, banco separado `sonarfy_avatar_test`, schema consolidado
  importado e Hibernate em `validate`. O SDK Azure real usa o mesmo servidor HTTP
  simulado descrito acima. Contêiner de teste removido ao terminar.
- Cobertura inclui proporções em retrato/paisagem, ausência de ampliação, EXIF
  retangular, metadados, isolamento entre avatar/capa, substituição, rollback,
  falha de armazenamento, limites de arquivo e segurança HTTP real. Configuração
  também verifica habilitação de wallpaper sem habilitar a rota de avatar.
- Coleção ampliada para 18 requisições, com JSON e scripts verificados.
  Postman manual, leitura pública e upload de wallpaper no Azure real ainda não
  executados. CI remoto desta nova branch não executado.
- Sem migration ou alterações no banco da aplicação, frontend, configuração local
  ou Azure real. Posicionamento responsivo e persistência de posição continuam
  fora do contrato. O lembrete local de frontend permanece fora do Git.

No teste manual do wallpaper, o usuário identificou que enviar dois arquivos
processava somente o primeiro. A validação foi corrigida em avatar e wallpaper:
ambos rejeitam o pedido inteiro com 400 quando houver mais de um arquivo, inclusive
em campos distintos. Testes pela segurança real verificam ausência de chamadas ao
armazenamento e preservação das duas referências. Após reiniciar a versão corrigida,
o usuário confirmou no Postman HTTP 400 nas duas rotas ao enviar múltiplas imagens.
Essa confirmação manual de status não inclui inspeção adicional dos blobs ou das
referências após a rejeição; a ausência de efeitos colaterais é coberta pelos testes.

Suíte completa após a correção: 717 testes reportados, zero falhas/erros e 65
ignorados. Os dois cenários novos cobrem avatar e wallpaper, cada um com arquivos
na mesma chave ou em chaves diferentes. Coleção de 20 requisições validada como
JSON e scripts; sem execução do runner ou repetição no PostgreSQL nesta correção.

O usuário também confirmou manualmente upload e leitura pública de wallpaper no
Azure real: original PNG 1366×768 resultou em 1067×600, e JPEG 950×885 em PNG
644×600. A nova URL foi retornada com avatar e texto preservados. A substituição
foi relatada no armazenamento, sem confirmação explícita de exclusão do blob
anterior nesta etapa. Testes negativos de formatos incompatíveis seguem pendentes
de execução manual. Estas evidências complementam o estado inicial descrito acima.

Na sequência manual, o usuário confirmou GIF e TXT renomeado para PNG com HTTP 415,
arquivo acima de 5 MiB com 413 e ausência da chave file com 400. A tentativa de arquivo
corrompido retornou 401, portanto não comprova validação de conteúdo e precisa ser
repetida com access token válido. Deixar o seletor de arquivo vazio retornou 415;
isso não equivale a enviar um arquivo de zero bytes e pode representar pedido sem
Content-Type multipart. Arquivos locais de teste de zero bytes e PNG de 20 MP abaixo
de 5 MiB foram preparados em target/manual-media-tests, fora do versionamento.

O usuário confirmou HTTP 400 para o arquivo de zero bytes e para o PNG válido de
5000×4000 (20 MP, abaixo de 5 MiB), ambos enviados à rota de wallpaper. Os casos
negativos desta sequência foram executados manualmente somente em wallpaper;
repetição manual equivalente no avatar fica para depois. Ambas as rotas compartilham
o processador de formato/bytes/pixels e possuem testes automatizados, mas isso não
constitui evidência de execução manual de cada rota. O usuário informou testes
anteriores de corrupção, sem novo resultado de conteúdo nessa sequência; a tentativa
com 401 não foi considerada validação de arquivo corrompido.
