# Sonarfy

O Sonarfy nasceu como Trabalho de Conclusão de Curso (TCC) do curso de Ciência da Computação da FAM - Centro Universitário. A proposta do projeto é criar uma plataforma para que pessoas apaixonadas por música possam descobrir, avaliar e compartilhar opiniões sobre álbuns, artistas e experiências sonoras.

O nome combina "sonar" e "for you", remetendo à ideia de um espaço musical pensado para cada usuário — algo que conecta som, comunidade e descoberta pessoal.

## Sobre o projeto

Este repositório é público para permitir a avaliação acadêmica pelo orientador
e pela banca do TCC, sem exigir acesso de colaborador. O Sonarfy não adota uma
licença open-source; a publicação tem finalidade de consulta e avaliação.
Esta documentação não concede acesso aos ambientes, contas ou serviços do projeto.

A [documentação da API](docs/api/README.md) e o [modelo de persistência](docs/persistence.md)
apresentam os contratos HTTP, os comandos de teste e as evidências disponíveis.
A aplicação está em desenvolvimento e não é apresentada como pronta para produção.

O Sonarfy é uma plataforma web voltada para:

- criar e gerenciar perfis de usuários;
- acompanhar outros perfis e interagir com a comunidade;
- descobrir novas obras musicais;
- escrever avaliações e compartilhar opiniões;
- acompanhar o que está sendo apreciado por outros usuários;
- aproveitar uma experiência musical mais social e personalizada.

A ideia central é transformar como as pessoas exploram e discutem música, reunindo comunidade, avaliação e descoberta em um ambiente único.

## Funcionalidades previstas

### Escopo desta versão

O feed faz parte do escopo desta versão. O sistema de notificações da plataforma,
likes, comentários e respostas não serão implementados nesta versão: são
funcionalidades futuras, sem prazo definido, e não são critérios de conclusão
da entrega atual. A implementação do feed não depende dessas interações.

O [feed único](docs/api/feed.md) reúne avaliações próprias e dos perfis
seguidos, incluindo publicações anteriores ao seguimento, em ordem decrescente
por ID. Não haverá abas Para você, Seguindo ou Seguidores. Editar não recoloca
no topo; excluir remove a publicação. Consulte o [guia de integração](docs/integrations/feed.md).

Essa decisão não altera os e-mails transacionais dos fluxos de conta e segurança.
Os itens da visão geral abaixo não constituem uma lista de pendências da versão;
disponibilidade e limitações devem ser conferidas nos contratos de cada funcionalidade.

### Página do álbum

O escopo backend implementado reúne detalhes do álbum, catálogo com busca no
Spotify quando faltar, média e total de avaliações elegíveis e comunidade paginada
com quatro ordenações. O gerenciamento da avaliação própria reutiliza os contratos
existentes. Notas permanecem em 0–10, apresentadas como estrelas de 0–5 na interface.

O usuário aceitou a entrega backend em 24/11/2026. A última implementação está na
branch `feat/album-community-reviews`, encaminhada para review, CI e
merge. A migration 019 foi validada em banco descartável e ainda precisa ser
aplicada ao banco local; 017 e 018 já foram aplicadas com autorização e backup.
Integração e validação visual do frontend são trabalho separado. Esse aceite
não representa a conclusão do projeto inteiro.

Consulte o [contrato do álbum](docs/api/albums.md), o
[guia de integração da página](docs/integrations/album-page.md) e o
[estado das migrations](docs/persistence.md).

### Visão geral do produto

- Cadastro e autenticação de usuários
- Perfil personalizado com bio, avatar e capa
- Busca por usuários e interação social
- Feed com conteúdos e atualizações
- Publicação de avaliações e resenhas
- Organização de álbuns favoritos
- Integração com serviços de música e catálogo externo

## Nossa proposta

O Sonarfy busca criar um espaço em que fãs de música, críticos e colecionadores possam:

- descobrir novos álbuns e artistas;
- compartilhar impressões com clareza e personalidade;
- acompanhar tendências e gostos da comunidade;
- construir uma identidade musical própria dentro da plataforma.

## Integração com Spotify

A entrega atual permite autorizar uma conta Spotify, redirecionar o callback ao
frontend com o desfecho da operação (`?spotify=connected|denied|invalid_state|error`),
consultar o estado da conexão e desconectá-la, com tokens armazenados cifrados e
suporte a rotação de chaves e renovação sob demanda. Ecos oferece sincronização
e consulta de escutas. A página do álbum usa Client Credentials da aplicação para
buscar metadados ausentes, sem exigir conexão Spotify pessoal; a comunidade é
uma leitura local. Busca por nome e consulta dedicada de artistas não integram
essa entrega. Consulte a
[configuração, o contrato de integração do frontend e o escopo](docs/integrations/spotify.md).

## Contratos da API

O projeto usa Java 21, Spring Boot 4.1.0 e java-jwt 4.4.0.
Consulte o [índice geral da documentação da API](docs/api/README.md) para o mapa completo dos endpoints e o isolamento dos seis propósitos de tokens JWT:

- [Cadastro, retificação de e-mail pendente e ativação de conta](docs/api/registration.md)
- [Login e autenticação HTTP Bearer](docs/api/authentication.md)
- [Recuperação e troca de senha](docs/api/passwords.md) e [coleção oficial do Postman](docs/api/sonarfy-passwords.postman_collection.json) (ajuste a variável `baseUrl`, ex.: `http://localhost:8080`)
- [Integração da troca autenticada de senha](docs/integrations/password-change.md): envio do link, cooldown, conclusão e novo login.
- [Conta e perfil do usuário (`/api/users/me` e `/api/profile/me`)](docs/api/users-and-profile.md)
- [Localização por cidade selecionada](docs/api/locations.md): busca pública, `cityId` no cadastro e país derivado; [configuração GeoNames](docs/integrations/locations.md).
- [Integração das configurações: dados pessoais](docs/integrations/user-settings.md), com [coleção Postman importável](docs/api/sonarfy-users.postman_collection.json).
- [Troca de e-mail: solicitação, confirmação e fila](docs/integrations/email-change.md) — ativação controlada; [contrato e coleção Postman](docs/api/email-changes.md).
- [Integração OAuth 2.0 com o Spotify](docs/integrations/spotify.md)

| Operação | Endpoint | Campos JSON |
| --- | --- | --- |
| Solicitar recuperação | `POST /api/auth/forgot-password` | `email` |
| Redefinir senha esquecida | `POST /api/auth/reset-password` | `token`, `newPassword`, `confirmNewPassword` |
| Solicitar link de troca autenticada | `POST /api/users/me/password-change-request` | Sem corpo |
| Trocar senha autenticada | `PUT /api/users/me/password` | `token`, `currentPassword`, `newPassword`, `confirmNewPassword` |

As requisições com corpo usam `Content-Type: application/json`. Na solicitação
pública, o e-mail vai no JSON. Recuperação e troca usam DTOs separados:
a recuperação não exige a senha esquecida; a troca exige a senha atual.

A solicitação autenticada usa o ID imutável para localizar o e-mail atual da conta.
O link enviado leva às configurações com o token no fragmento da URL. A leitura
desse fragmento e a integração do botão no frontend permanecem para outra etapa.
Após concluir a troca, o cliente deve descartar as credenciais locais e solicitar
novo login; todos os refresh tokens são revogados.

As rotas `/api/users/**` exigem o token de acesso retornado por `POST /api/auth/login`,
enviado no cabeçalho `Authorization: Bearer <accessToken>`. O filtro estabelece a
identidade da conta ativa e verificada. O token enviado no JSON autoriza a operação
de senha e não substitui o Bearer. Consulte o [contrato de autenticação](docs/api/authentication.md)
para a lista explícita de rotas públicas e a configuração de CORS.

## Modelo de persistência

O cadastro gera o nome de exibição a partir de nome e sobrenome, limitado a 60
caracteres. Consulte o [modelo de persistência e a validação do schema importado](docs/persistence.md)
para os mapeamentos e o teste opcional contra o PostgreSQL existente.

## E-mails de conta e intervalo entre tentativas

Ativação, recuperação e troca autenticada de senha compartilham um intervalo mínimo de 30 segundos,
configurável por `app.email.cooldown-seconds`. O campo `lastEmailSentAt` registra
a última tentativa autorizada, não a confirmação de entrega. A reserva é confirmada
no banco antes do envio e permanece válida mesmo se o SMTP falhar. Um envio de
ativação pode, portanto, impedir temporariamente um envio de recuperação ou troca de senha.
Na solicitação autenticada, o cooldown retorna 429, sem `Retry-After`;
sucesso no envio retorna 200 e não comprova entrega na caixa de entrada.

Cadastro e alteração de e-mail pendente publicam um evento dentro da transação.
O envio de ativação ocorre após o commit: falhas de entrega não desfazem o cadastro
nem a alteração. O usuário pode solicitar reenvio após o intervalo. A recuperação
pública mantém a mesma resposta para contas inelegíveis, cooldown e falha de entrega.

Os eventos ficam em memória e o listener é síncrono. Se a aplicação encerrar entre
o commit e o envio, não há recuperação automática da mensagem; é necessário pedir
reenvio. A solução não garante entrega nem elimina diferenças de tempo de resposta.

### Testes da reserva de e-mail

`.\mvnw.cmd test` no Windows (ou `./mvnw test` em Unix) executa os testes unitários
e de integração, usando H2 isolado por padrão.
Os testes de integração usam serviços, proxies Spring e transações reais, com
transporte simulado. Para validar concorrência também em PostgreSQL, use um banco
descartável exclusivo: o contexto de testes cria e remove as tabelas mapeadas.
Nunca forneça a URL do banco de desenvolvimento ou de produção.

Exemplo em PowerShell, com Docker:

```powershell
docker run --detach --rm --name sonarfy-task2-tests -e POSTGRES_PASSWORD=sonarfy_test_only -e POSTGRES_DB=sonarfy_task2_tests -p 127.0.0.1:55432:5432 postgres:17.9
docker exec sonarfy-task2-tests pg_isready -U postgres -d sonarfy_task2_tests
# Execute o Maven depois que pg_isready indicar que o banco aceita conexões.
mvn test "-Dsonarfy.test.jdbc-url=jdbc:postgresql://127.0.0.1:55432/sonarfy_task2_tests" "-Dsonarfy.test.db-user=postgres" "-Dsonarfy.test.db-password=sonarfy_test_only"
docker stop sonarfy-task2-tests
```

Relatórios: `target/surefire-reports`. A senha acima é exclusiva do banco temporário.

## Uso único dos tokens de alteração de senha

A troca autenticada e a recuperação de senha bloqueiam a linha do usuário até o fim
da transação. Após obter o bloqueio, o estado é recarregado antes de validar o token
e a senha atual. Senha e horário de alteração são confirmados juntos; rollback
preserva os valores anteriores e permite uma nova tentativa com o token válido.

A biblioteca `java-jwt:4.4.0` serializa `iat` em segundos inteiros. Tokens de
recuperação (`password_reset`) e de troca autenticada (`password_change`) têm
propósitos distintos e são aceitos somente em seus respectivos fluxos. Um token só é
aceito se sua emissão for estritamente posterior à última alteração de senha.
Igualdade é rejeitada: um token novo emitido no mesmo segundo da alteração (ou do
cadastro) também pode ser recusado. Tokens sem `iat`, expirados ou com outra
finalidade são rejeitados.

O controle por horário pressupõe relógios coerentes entre instâncias e invalida
os tokens anteriores ou iguais ao corte. Troca e recuperação de senha também
revogam todas as sessões de refresh do usuário na mesma transação.
`PasswordResetIntegrationTest` verifica reutilização com senhas diferentes,
concorrência nos dois fluxos, estado previamente carregado, limites temporais e
rollback com JWTs reais e banco. Pode ser executado com H2 ou com as propriedades
de PostgreSQL descritas acima.

## Status do projeto

O projeto está em desenvolvimento. O backend já possui fluxos de cadastro,
ativação, autenticação e sessões, atualização de dados pessoais, recuperação e
troca de senha, troca verificada de e-mail e conexão com Spotify, com contratos e
limitações descritos nos guias acima. As funcionalidades sociais listadas como
previstas e a integração das telas evoluem em entregas separadas. A disponibilidade
de um endpoint não significa que o fluxo correspondente já esteja integrado no frontend.

## Observação

O código e a documentação técnica deste repositório são públicos. Exemplos usam
dados fictícios e recursos locais de teste; credenciais reais, tokens em uso,
configurações pessoais e dados de usuários não fazem parte dos entregáveis.
As limitações descritas permitem avaliar o estado real da implementação.

---

Desenvolvido como projeto acadêmico e base para uma futura plataforma completa de avaliação musical.


## Persistência de sessões de autenticação

A camada de refresh tokens guarda hashes e histórico por sessão de dispositivo,
com rotação e revogação serializadas. Reutilizar um token consumido encerra a
sessão afetada; as demais sessões são preservadas. Execute as migrations 003,
004 e 005 em bancos existentes, conforme [persistência](docs/persistence.md).
A preferência `remember_me` é armazenada por sessão e preservada na rotação;
o padrão é `false`. A validade continua em 30 dias por inatividade, sem prazo absoluto.
Login aceita `rememberMe` e emite refresh em cookie HttpOnly; `POST /api/auth/refresh`
rotaciona a sessão e `POST /api/auth/logout` a encerra com 204 e limpa o cookie.
Os três endpoints exigem `X-Sonarfy-CSRF: 1` e o navegador deve
enviar `credentials: "include"`. Em HTTP local, configurar explicitamente
`AUTH_REFRESH_COOKIE_SECURE=false`; o padrão é `true`. Configurar origens exatas
em `AUTH_ALLOWED_ORIGINS` (local: `http://localhost:63342,http://localhost:8080`).
Veja [autenticação](docs/api/authentication.md) e [integração](docs/integrations/authentication.md).
Logout aceita token atual ou consumido e preserva outros dispositivos. O cliente deve
descartar seu access token, que pode continuar válido por até 15 minutos.
O checkbox e a integração do logout no frontend permanecem para próximas entregas.
Troca e recuperação de senha revogam todas as sessões de refresh do usuário na
mesma transação. Access tokens já emitidos continuam sujeitos à validade de 15 minutos.
A CI exercita a rotação e a concorrência tanto em H2 quanto em PostgreSQL.
