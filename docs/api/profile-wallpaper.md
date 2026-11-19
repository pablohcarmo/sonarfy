# Upload de wallpaper

POST `/api/profile/me/wallpaper`, com Bearer access de conta ativa e verificada.
Enviar multipart/form-data com exatamente um arquivo na parte File chamada `file`.
Dois ou mais arquivos, mesmo em campos distintos, retornam 400 sem processamento
parcial ou gravação no armazenamento. Exige `app.wallpaper.enabled=true`.
Sem configuração explícita o recurso não publica a rota. O próprio usuário é
selecionado pelo principal, independentemente de IDs ou nomes enviados.

## Política e resposta

- JPEG/PNG estático, até 5 MiB e 16 milhões de pixels na imagem de entrada.
- Aplica orientação EXIF e remove metadados antes de publicar PNG.
- Preserva a imagem inteira, sem recorte ou distorção. Reduz proporcionalmente
  para caber em 1600×600, sem ampliar imagens menores. Arredonda dimensões para
  baixo, com mínimo de um pixel por lado.
- Exemplos: 3200×1200 → 1600×600; 1200×800 → 900×600;
  1200×2400 → 300×600; 225×225 → 225×225.
- Não há coordenadas de recorte ou persistência de posição neste contrato.
  O enquadramento responsivo e a seleção de posição pertencem ao frontend.
- HTTP 200 retorna `ProfileDto`, com `identity.coverUrl` persistido e
  `Cache-Control: no-store`. Avatar, texto e dados cadastrais permanecem iguais.
- HTTP 400: arquivo ausente, vazio, corrompido, animado ou com pixels excedidos.
  HTTP 401: credencial ausente, inválida ou usuário inelegível.
  HTTP 413: bytes excedidos. HTTP 415: formato não suportado ou pedido sem multipart.
  HTTP 503: upload indisponível, inclusive rejeição ou timeout do armazenamento.

## Armazenamento e consistência

Usa o mesmo cliente Azure e processamento seguro de imagem do avatar. Os objetos
têm chaves `wallpapers/{userId}/{UUID}.png`; a URL fica em `users.wallpaper`, exposta
como `identity.coverUrl`. Não exige migration nem cria container automaticamente.

Upload ocorre antes da transação. Ao associar o blob, o servidor bloqueia/recarrega
o usuário e revalida sua elegibilidade. Após commit, tenta remover apenas a capa
anterior cujo prefixo, conta, container, usuário e UUID sejam gerenciados pelo recurso.
Um blob do prefixo `avatars` não é excluído ao substituir wallpaper.

Rollback confirmado tenta remover a nova imagem; upload malsucedido não substitui
a referência atual. Limpeza é por tentativa e pode deixar órfãos em falha de processo,
exclusão ou resultado desconhecido. Não há retry automático de upload nem transação
distribuída. Valem as mesmas [garantias e limites de limpeza do avatar](profile-avatar.md).

Configuração, testes e roteiro manual: [mídia de perfil](../integrations/profile-media.md).
Coleção: [sonarfy-profiles.postman_collection.json](sonarfy-profiles.postman_collection.json).
