# Upload de avatar

POST `/api/profile/me/avatar`, autenticado por Bearer access de usuário ativo e
verificado. Disponível somente quando `app.avatar.enabled=true`, com a configuração
de armazenamento válida. O recurso desabilitado não publica a rota de upload.

## Requisição e política inicial

Enviar `multipart/form-data`, com uma parte de arquivo chamada `file`. Deixar o
cliente gerar o boundary; não enviar JSON ou base64. A identidade vem somente do
Bearer. Nome original do arquivo, parâmetros de usuário e extensão não escolhem
conta, caminho ou formato persistido.

| Regra | Padrão |
| --- | --- |
| Conteúdo aceito | JPEG ou PNG estáticos, identificados pelo decoder |
| Tamanho de entrada | Até 5 MiB (5.242.880 bytes) |
| Pixels de entrada | Até 16.000.000, antes da decodificação completa |
| Proporção | Livre; recorte quadrado central por padrão, ou seleção explícita |
| Saída | PNG, 512×512 pixels, sem metadados originais |

SVG, GIF, WebP e PNG animado não integram a política inicial. O MIME declarado não
substitui a inspeção do conteúdo. As oito orientações EXIF são aplicadas antes da
redução; o recorte não distorce a proporção. PNG preserva transparência. Limites de bytes,
pixels, resolução final e timeout são configuráveis; ampliar formatos requer
suporte explícito no processador e testes.

## Respostas

- 200: `ProfileDto`, como no [GET de perfil](profiles.md), com `identity.avatarUrl`
  atualizado, `viewer.isSelf=true`, `canEdit=true` e `Cache-Control: no-store`.
- 400: parte `file` ausente/vazia, imagem corrompida, animada, recorte inválido ou acima
  do limite de pixels.
- 401: credencial ausente, inválida ou de outro propósito, ou usuário inelegível.
- 413: arquivo acima do limite de bytes; limite global da requisição permite
  adicionalmente 64 KiB de envelope multipart.
- 415: conteúdo fora dos formatos suportados ou Content-Type externo incompatível
  com multipart. MIME e extensão da parte não são usados como prova de formato.
- 503: falha de upload, autenticação do armazenamento, timeout ou rejeição do Azure.
  A mensagem externa não inclui a resposta interna do provedor.
- Falhas de banco não são convertidas em sucesso; não há gravação parcial do perfil.

## Persistência e substituição

Os campos multipart Text `cropX`, `cropY` e `cropSize` são opcionais, mas devem ser
enviados juntos. Representam coordenadas inteiras e lado quadrado positivo em pixels
da imagem original após orientação EXIF. Sem eles, usa o menor lado e centraliza,
arredondando a margem para baixo. Não aceitar seleção fora dos limites, coordenadas
negativas ou valores parciais/não inteiros (400). Bytes e pixels são limitados antes
do recorte. A interface de seleção do recorte no frontend permanece pendente.

O servidor gera `avatars/{userId}/{UUID}.png` no container configurado. Upload usa
criação condicional, sem sobrescrever outro blob. A URL pública estável é persistida
na coluna existente `users.avatar` (VARCHAR 255). URLs com SAS não são persistidas.
O blob normalizado usa `Content-Type: image/png` e cache público imutável por um ano;
substituições geram outra URL. Dados cadastrais, bio, nome e wallpaper são preservados.

Não há transação distribuída entre Azure e PostgreSQL:

1. Validar e normalizar localmente, antes de chamar o armazenamento.
2. Enviar o novo blob, sem manter bloqueio de usuário durante o upload.
3. Recarregar/bloquear o usuário, revalidar elegibilidade, alterar somente avatar
   e confirmar a transação.
4. Após commit, tentar remover o avatar anterior somente se sua URL corresponder
   ao container, usuário e padrão de chaves gerenciado por este recurso.

Rollback confirmado tenta excluir o novo objeto; falha de upload também tenta
limpeza, exceto conflitos de criação. Resultado transacional desconhecido não
exclui o novo objeto, evitando remover uma imagem possivelmente referenciada.
Falha de limpeza é registrada por chave de objeto, sem credenciais, e não desfaz
um commit bem-sucedido. Não há retry automático do upload; timeout pode ter resultado
remoto ambíguo. Falha de processo, resultado desconhecido ou limpeza indisponível
podem deixar órfãos: reconciliação durável/agendada fica para outra entrega.

Blobs antigos em cache público podem continuar acessíveis após exclusão no Azure.
Não há endpoint de remoção nesta fatia. Wallpaper e capas Spotify permanecem fora
do upload de avatar. [Configuração e roteiro manual](../integrations/profile-media.md).
