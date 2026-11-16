# Integração de sessões de autenticação

Contrato HTTP: [autenticação](../api/authentication.md). Frontend local:
`http://localhost:63342/sonarfy-frontend/pages`; API: `http://localhost:8080`.

O serviço interno aceita `issue(user, rememberMe)`; a emissão sem preferência
usa `false`. A escolha fica persistida em cada token da sessão e é copiada na
rotação. `RotationDto.rememberMe()` fornece esse valor ao adaptador HTTP;
não deve ser substituído por um parâmetro de refresh enviado pelo cliente.

Os dois modos mantêm 30 dias por inatividade e não têm prazo absoluto.
A integração HTTP usa a preferência para distinguir cookie de sessão
de cookie persistente. O frontend deverá coordenar renovações entre chamadas e
abas: reutilizar um token consumido encerra a sessão afetada.

## Configuração

`AUTH_ALLOWED_ORIGINS` recebe origens exatas separadas por vírgula, sem caminhos
ou wildcards. Padrão local: `http://localhost:63342,http://localhost:8080`.
`AUTH_REFRESH_COOKIE_SECURE` tem padrão `true`; produção exige HTTPS. Usar `false`
explicitamente apenas para desenvolvimento HTTP e reiniciar a API após mudar.
API e frontend devem estar no mesmo site. Não misturar localhost e 127.0.0.1.

## Login e refresh no navegador

```javascript
const response = await fetch("http://localhost:8080/api/auth/login", {
  method: "POST",
  credentials: "include",
  headers: { "Content-Type": "application/json", "X-Sonarfy-CSRF": "1" },
  body: JSON.stringify({ login, password, rememberMe: checkbox.checked })
});
```

Em `200`, guardar o access token em memória e usá-lo como Bearer nas rotas
protegidas. O navegador gerencia o refresh HttpOnly; scripts não devem ler
Set-Cookie, nem copiar o refresh para localStorage/sessionStorage.
`rememberMe=false` (padrão) emite cookie de sessão; `true` emite cookie persistente
por 30 dias. Restauração do navegador pode preservar cookies de sessão.

```javascript
const response = await fetch("http://localhost:8080/api/auth/refresh", {
  method: "POST",
  credentials: "include",
  headers: { "X-Sonarfy-CSRF": "1" }
});
```

Em `200`, substituir o access token em memória. Em `401`, apagar a credencial
local e exigir login; o servidor limpa o cookie. Em `403`, corrigir origem ou
cabeçalho CSRF; nenhuma rotação ocorreu. Rede/5xx não garantem que a rotação não
tenha sido confirmada: não repetir refresh automaticamente.

Coordenar chamadas e abas, por exemplo com Web Locks e BroadcastChannel,
transmitindo o novo access token somente entre contextos da mesma origem.
Um bloqueio sozinho não basta: cada aba deve reavaliar a credencial compartilhada
após aguardar, evitando outra rotação desnecessária. Compartilhar uma única
operação em andamento entre requisições que receberam 401.

## Validação e limites

Postman/curl precisam do cabeçalho CSRF e de um cookie jar. Não registrar senhas,
tokens ou cabeçalhos Cookie, Set-Cookie e Authorization. Postman não reproduz
CORS/SameSite do navegador; validar ambos.

A [coleção importável](../api/sonarfy-authentication.postman_collection.json)
executa login e refresh em sequência. Preencher `baseUrl`,
`authLogin` e `authPassword` apenas no ambiente local do Postman e não exportar
credenciais. O cookie jar deve estar habilitado. Em CLI local, usar
`postman collection run docs/api/sonarfy-authentication.postman_collection.json`
com variáveis do ambiente e `--no-report-events` para não publicar dados do teste.

Os scripts Post-response aceitam `authExpectedStatus` (padrão `200`; configurar
`400`, `401` ou `403` antes de executar um cenário negativo). Restaurar `200`
ao voltar ao caminho de sucesso. O status esperado é declarado pelo teste, não
inferido da resposta. `authSecureCookie` tem padrão `true`; usar `false` somente
quando a API de teste tiver Secure explicitamente desabilitado.

O login lê `rememberMe` do body após resolver as variáveis: `true` exige
`Max-Age=2592000`; `false` ou omitido exige ausência de Max-Age e Expires.
O script salva a preferência em `authSessionRememberMe` para validar sua
preservação no refresh. Execute login antes de validar refresh com sucesso.
Os dois scripts verificam o contrato JSON, atributos do cookie e no-store.
Em 401 de refresh, verificam cookie vazio com Max-Age=0; em login recusado
ou 403, verificam ausência de Set-Cookie. Erros não devem expor credenciais.

Scripts locais em `postman/` são ignorados pelo Git. Para atualizar uma coleção
importada no aplicativo Postman, reimporte o JSON versionado ou copie seu script
Post-response para a requisição equivalente. Alterar arquivos locais não altera
automaticamente uma coleção independente salva na nuvem.

O JWT já emitido continua sujeito à expiração de 15 minutos e à situação atual
da conta; não há blacklist de access tokens. Troca e recuperação de senha revogam
todas as sessões de refresh do usuário, de modo atômico com a atualização da
senha. Ao concluir qualquer desses fluxos, descartar o access token local e
exigir novo login. Outros dispositivos receberão 401 na próxima renovação,
com limpeza do cookie. O cookie HttpOnly não precisa ser apagado por JavaScript.
O checkbox e a coordenação de chamadas pertencem ao repositório do frontend.
`/api/auth/logout` fica para a próxima entrega. Apagar apenas o access token não
revoga a sessão no servidor.
