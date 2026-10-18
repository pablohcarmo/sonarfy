# Sonarfy

O Sonarfy nasceu como Trabalho de Conclusão de Curso (TCC) do curso de Ciência da Computação da FAM - Centro Universitário. A proposta do projeto é criar uma plataforma para que pessoas apaixonadas por música possam descobrir, avaliar e compartilhar opiniões sobre álbuns, artistas e experiências sonoras.

O nome combina "sonar" e "for you", remetendo à ideia de um espaço musical pensado para cada usuário — algo que conecta som, comunidade e descoberta pessoal.

## Sobre o projeto

Este repositório é público para permitir a avaliação acadêmica pelo orientador
e pela banca do TCC, sem exigir acesso de colaborador. O Sonarfy não adota uma
licença open-source; a publicação tem finalidade de consulta e avaliação.
Esta documentação não concede acesso aos ambientes, contas ou serviços do projeto.

O [guia de avaliação](docs/avaliacao-academica.md) apresenta os comandos de teste,
as evidências disponíveis e um roteiro de demonstração. A aplicação está em
desenvolvimento e não é apresentada como pronta para produção.

O Sonarfy é uma plataforma web voltada para:

- criar e gerenciar perfis de usuários;
- acompanhar outros perfis e interagir com a comunidade;
- descobrir novas obras musicais;
- escrever avaliações e compartilhar opiniões;
- acompanhar o que está sendo apreciado por outros usuários;
- aproveitar uma experiência musical mais social e personalizada.

A ideia central é transformar como as pessoas exploram e discutem música, reunindo comunidade, avaliação e descoberta em um ambiente único.

## Funcionalidades previstas

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

## Contrato da API de senhas

O projeto usa Java 21, Spring Boot 4.1.0 e java-jwt 4.4.0.
Consulte o [contrato de recuperação e troca de senha](docs/api/passwords.md)
para os campos obrigatórios, exemplos JSON, respostas e limites de autenticação.
Importe a [coleção oficial do Postman](docs/api/sonarfy-passwords.postman_collection.json)
e ajuste a variável `baseUrl` (exemplo local: `http://localhost:8081`).

| Operação | Endpoint | Campos JSON |
| --- | --- | --- |
| Solicitar recuperação | `POST /api/auth/forgot-password` | `email` |
| Redefinir senha esquecida | `POST /api/auth/reset-password` | `token`, `newPassword`, `confirmNewPassword` |
| Solicitar token de troca autenticada | `POST /api/users/me/password-change-request` | Sem corpo |
| Trocar senha autenticada | `PUT /api/users/me/password` | `token`, `currentPassword`, `newPassword`, `confirmNewPassword` |

As requisições com corpo usam `Content-Type: application/json`. Na solicitação
pública, o e-mail vai no JSON. Recuperação e troca usam DTOs separados:
a recuperação não exige a senha esquecida; a troca exige a senha atual.

As rotas `/api/users/**` exigem identidade autenticada, mas o código atual ainda
não configura um mecanismo de autenticação HTTP que a estabeleça. O token enviado
no JSON não substitui essa autenticação. A coleção documenta esse impedimento;
não presume um JWT Bearer de acesso funcional.

## E-mails de conta e intervalo entre tentativas

Ativação e recuperação de senha compartilham um intervalo mínimo de 30 segundos,
configurável por `app.email.cooldown-seconds`. O campo `lastEmailSentAt` registra
a última tentativa autorizada, não a confirmação de entrega. A reserva é confirmada
no banco antes do envio e permanece válida mesmo se o SMTP falhar. Um envio de
ativação pode, portanto, impedir temporariamente um envio de recuperação.

Cadastro e alteração de e-mail pendente publicam um evento dentro da transação.
O envio de ativação ocorre após o commit: falhas de entrega não desfazem o cadastro
nem a alteração. O usuário pode solicitar reenvio após o intervalo. A recuperação
pública mantém a mesma resposta para contas inelegíveis, cooldown e falha de entrega.

Os eventos ficam em memória e o listener é síncrono. Se a aplicação encerrar entre
o commit e o envio, não há recuperação automática da mensagem; é necessário pedir
reenvio. A solução não garante entrega nem elimina diferenças de tempo de resposta.

### Testes da reserva de e-mail

`mvn test` executa os testes unitários e de integração, usando H2 isolado por padrão.
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

A biblioteca `java-jwt:4.4.0` serializa `iat` em segundos inteiros. Um token só é
aceito se sua emissão for estritamente posterior à última alteração de senha.
Igualdade é rejeitada: um token novo emitido no mesmo segundo da alteração (ou do
cadastro) também pode ser recusado. Tokens sem `iat`, expirados ou com outra
finalidade são rejeitados.

O controle por horário pressupõe relógios coerentes entre instâncias e invalida
os tokens anteriores ou iguais ao corte; ele não revoga sessões de login.
`PasswordResetIntegrationTest` verifica reutilização com senhas diferentes,
concorrência nos dois fluxos, estado previamente carregado, limites temporais e
rollback com JWTs reais e banco. Pode ser executado com H2 ou com as propriedades
de PostgreSQL descritas acima.

## Status do projeto

O projeto está em desenvolvimento, com foco inicial na modelagem de funcionalidades, experiência do usuário e prototipação das principais telas.

## Observação

O código e a documentação técnica deste repositório são públicos. Exemplos usam
dados fictícios e recursos locais de teste; credenciais reais, tokens em uso,
configurações pessoais e dados de usuários não fazem parte dos entregáveis.
As limitações descritas permitem avaliar o estado real da implementação.

---

Desenvolvido como projeto acadêmico e base para uma futura plataforma completa de avaliação musical.
