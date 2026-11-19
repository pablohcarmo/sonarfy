# Pendente no frontend: seleção do recorte de avatar

O backend aceita JPEG/PNG retangular ou quadrado no POST `/api/profile/me/avatar`.
O frontend ainda não foi alterado. Implementar seleção de área quadrada, zoom e
prévia circular antes de enviar a imagem. Sem seleção explícita, o backend faz
recorte quadrado central e redimensiona para 512×512, sem distorcer a proporção.

## Contrato para o adapter

Enviar multipart/form-data com Bearer e arquivo original na parte `file`.
Opcionalmente enviar os três campos Text juntos:

- `cropX`: distância da borda esquerda, em pixels inteiros.
- `cropY`: distância da borda superior, em pixels inteiros.
- `cropSize`: lado do quadrado, em pixels inteiros positivos.

As coordenadas referem-se à imagem com orientação EXIF já aplicada, em resolução
original, não às dimensões CSS da prévia. Para orientações EXIF 5–8, largura e altura
são trocadas. Converter a seleção visual para esse sistema de coordenadas, sem
aplicar a rotação duas vezes. O arquivo original é enviado sem alteração.

Exemplo para imagem orientada de 1200×800: `cropX=400`, `cropY=100`, `cropSize=600`.
Enviar todos os campos ou omitir todos. Valores parciais, não inteiros, negativos,
lado zero ou seleção fora da imagem retornam 400. A seleção não pode ultrapassar
largura/altura. O recorte central usa o menor lado, com margem arredondada para baixo.

Limites continuam aplicados ao arquivo original: 5 MiB e 16 milhões de pixels,
JPEG/PNG estáticos. Recorte pequeno não permite enviar original acima desses limites.
Não definir manualmente Content-Type multipart; deixar o navegador gerar o boundary.
Em sucesso, reconciliar a interface com o ProfileDto, incluindo identity.avatarUrl.
Em falha, manter rascunho/prévia e permitir nova tentativa explícita.

## Validação da interface

- Retrato, paisagem, quadrada, transparência e orientações EXIF.
- Recorte nas bordas, zoom e correspondência entre prévia e imagem publicada.
- Cancelar sem upload e preservar o avatar atual em erro.
- Firefox e Edge, desktop/mobile, temas claro/escuro, conforme AGENTS.md do frontend.

Não aplicar esta regra às capas do Spotify: elas devem manter a arte completa.
Wallpaper será tratado em uma entrega própria.
