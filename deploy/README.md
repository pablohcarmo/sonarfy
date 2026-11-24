# Contêiner do backend

O contexto Docker aceita somente o JAR preparado, sem os arquivos locais
`application*.properties` ou YAML. A configuração de execução é montada de
fora da imagem. Este pacote implanta backend e banco; a publicação dos arquivos
estáticos do frontend é separada.

## Estado verificado na VM em 24/11/2026

O backend e o PostgreSQL 17.9 foram implantados em `/opt/sonarfy`, na VM
`vm-sonarfy-dev-001`, usando `deploy/compose.yaml`. Ambos passaram nos healthchecks.
O backend publica somente `127.0.0.1:8080`; o banco usa o volume persistente
`sonarfy_postgres_data` e não publica porta no host. O PostgreSQL nativo da VM
não foi substituído por este deploy.

O usuário configurou o DuckDNS, o certificado e a publicação estática do
frontend. A inspeção confirmou:

- URL pública: <https://sonarfymusic.duckdns.org>.
- Registro DNS A apontando para o IP público da VM na data da conferência.
- Nginx servindo os arquivos de `/var/www/sonarfy-frontend` e encaminhando
  `/api/` para `http://127.0.0.1:8080/api/`, preservando o prefixo.
- HTTP redirecionando para HTTPS; certificado Let's Encrypt válido até
  22/02/2027 e `certbot.timer` ativo. A renovação por dry-run não foi executada.
- `app.base-url` na origem pública, `app.frontend-url` em `/pages`, CORS na
  origem HTTPS e callback Spotify em `/api/spotify/callback`.
- `server.forward-headers-strategy=framework` e cookie de refresh com `Secure`.

Credenciais de desenvolvimento de SMTP e Spotify foram reutilizadas com
autorização do usuário. Foram gerados segredos próprios para banco, JWT e cifra
Spotify. Os valores ficam no arquivo externo de configuração e no secret do
banco; não fazem parte da imagem nem devem ser versionados.

O profile Maven `container` passou com 890 testes reportados: 726 executados,
164 ignorados e zero falhas/erros. A verificação externa confirmou frontend e
arquivos estáticos com HTTP 200, rota protegida sem credenciais com 401, login
vazio com origem e CSRF válidos com 400 e preflight de origem externa com 403.
Esses resultados não comprovam envio SMTP ou todos os fluxos autenticados.

### Correção dos cabeçalhos do Nginx

Em `/etc/nginx/sites-available/sonarfy`, o bloco `location /api/` passou a conter:

```nginx
proxy_hide_header X-Frame-Options;
proxy_hide_header X-Content-Type-Options;
add_header X-Frame-Options "DENY" always;
add_header X-Content-Type-Options "nosniff" always;
add_header Referrer-Policy "strict-origin-when-cross-origin" always;
```

O proxy remove as cópias recebidas do backend e emite uma única política para
a API, inclusive em erros gerados pelo próprio Nginx. Os cabeçalhos são
declarados nesse bloco porque `add_header` nele interrompe a herança dos
`add_header` do bloco `server`, conforme a
[documentação do Nginx](https://nginx.org/en/docs/http/ngx_http_headers_module.html#add_header).
As páginas estáticas mantêm `X-Frame-Options: SAMEORIGIN`.

`nginx -t` passou e `systemctl reload nginx` foi executado. Foram conferidos por
HTTPS a API com 401, a página inicial e o login com 200 e uma página inexistente
com 404: cada resposta apresentou somente um `X-Frame-Options`, um
`X-Content-Type-Options` e um `Referrer-Policy`, com os valores esperados.
Backup anterior à alteração:
`/var/backups/sonarfy-nginx-20261124T155140Z.conf`.

A limitação de acesso à sincronização Spotify é independente do proxy; veja
[restrições do provedor](../docs/integrations/spotify.md#restrições-do-provedor-e-publicação--24112026).

## Preparação

Na raiz do repositório, executar:

```powershell
.\deploy\prepare-image.ps1
docker compose -f deploy/compose.yaml build backend
```

`-SkipTests` permite empacotar após uma suíte já aprovada. O profile Maven
`container` usa `target/container-build/` e exclui configurações locais durante
a cópia de recursos. O script verifica e copia o JAR resultante para
`target/container/app.jar`, preservando o build convencional.

## Configuração e inicialização

1. Criar `deploy/config/`, ignorado pelo Git.
2. Copiar `application.properties.example` para
   `deploy/config/application.properties` e preencher todos os valores
   `REPLACE_*`. Gerar segredos independentes para JWT e cifra Spotify.
3. Gravar a senha do banco em `deploy/config/db_password`, sem quebra de linha,
   e usar a mesma senha em `spring.datasource.password`.
4. No Linux, restringir o diretório a root e ao grupo 10001. O arquivo de
   configuração deve ser legível pelo UID 10001 do backend; o arquivo de senha
   do PostgreSQL pode permanecer legível somente por root.
5. Executar `docker compose -f deploy/compose.yaml up -d --wait`.

O PostgreSQL usa volume persistente e não publica porta no host. O schema
`db/tables.sql`, com as migrations até 019 incorporadas, é importado somente
na primeira inicialização de um volume vazio. Em atualizações, aplicar as
migrations pendentes separadamente; não reimportar o schema completo.

O backend publica somente `127.0.0.1:8080`, para integração com o proxy HTTPS
da VM. Configurar domínio, certificado TLS, proxy reverso e origens do frontend
antes de disponibilizar autenticação no navegador. O callback Spotify precisa
estar cadastrado no provedor com a mesma URL da configuração.

Os limites de memória são destinados à VM de desenvolvimento. SMTP, Spotify,
GeoNames e Azure Blob exigem configuração e validação próprias. Uploads e as
flags opcionais de conta permanecem desabilitados no exemplo.

## Verificação e atualização

```sh
docker compose -f deploy/compose.yaml ps
docker compose -f deploy/compose.yaml logs --tail=100 backend
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/profile/me
```

O healthcheck espera HTTP 401 sem credenciais na rota protegida. Isso confirma
que o HTTP e a segurança iniciaram; não valida e-mail ou os provedores externos.
Hibernate valida o schema na inicialização.

Para atualizações, definir `SONARFY_IMAGE_TAG` com uma versão identificável,
construir a nova imagem e executar `up -d --wait`. Preservar a imagem anterior,
a configuração e um backup do banco para rollback. Não usar `down -v`, pois
remove o volume de dados.

As senhas são montadas em arquivos, conforme a
[documentação de secrets do Docker Compose](https://docs.docker.com/compose/how-tos/use-secrets/).
Esses arquivos precisam de proteção e backup no host; Compose não os cifra.

## Banco no Azure — 24/11/2026

O contêiner PostgreSQL da VM `vm-sonarfy-dev-001` foi inicializado com o schema
consolidado que incorpora as migrations 001–019. A comparação de dumps de
estrutura com um banco temporário criado a partir de `db/tables.sql` confirmou
igualdade de tabelas, colunas, defaults, constraints, índices e sequências.
O banco temporário foi removido após a conferência.

Na inicialização, foram verificadas 19 tabelas, zero registros nas tabelas de negócio e
somente as permissões iniciais `ROLE_ADMIN`, `ROLE_MODERATOR` e `ROLE_USER`.
Nenhum cadastro local foi importado. Backend e PostgreSQL permaneceram saudáveis.
As migrations antigas não foram reexecutadas individualmente sobre o schema
consolidado, pois seus objetos já existem.
