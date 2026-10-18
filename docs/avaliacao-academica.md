# Guia de avaliação acadêmica

O Sonarfy é disponibilizado para consulta e avaliação do TCC. O repositório não
adota licença open-source. A avaliação não requer credenciais de ambientes reais,
acesso de colaborador ou dados de usuários.

## Materiais

- [README](../README.md): apresentação, versões e políticas do fluxo de senhas.
- [Contrato da API](api/passwords.md): entradas, respostas, DTOs e limitações.
- [Coleção Postman](api/sonarfy-passwords.postman_collection.json): exemplos fictícios.
- Testes em `src/test/java`: regras de negócio e cenários reproduzíveis.

O rascunho local de descrição do PR não integra os entregáveis versionados.

## Executar os testes

Pré-requisitos: JDK 21 e terminal na raiz do repositório. O Maven Wrapper do projeto
usa Maven 3.9.15 e pode precisar de internet para baixar dependências.

No Windows:

```powershell
java -version
.\mvnw.cmd test
```

No Linux/macOS:

```sh
java -version
./mvnw test
```

Também é possível usar `mvn test` com Maven instalado. A execução padrão usa H2
isolado. Os testes de integração criam o schema de teste e simulam o transporte
de e-mail: não exigem senha SMTP ou envio real.

Para PostgreSQL 17.9, siga o exemplo Docker do README, com banco descartável
exclusivo. Os testes criam e removem tabelas; não use um banco com dados que devam
ser preservados. A senha demonstrativa do exemplo pertence somente ao container
local temporário.

Os resultados são gerados em `target/surefire-reports`. Para registrar uma nova
avaliação, anote `git rev-parse HEAD`, `git status --short`, data, versões,
comando e código de saída. Preserve os relatórios antes de executar `mvn clean`.
Revise o conteúdo antes de anexar logs; não publique configurações ou tokens reais.

## Registro da validação já realizada

As execuções abaixo foram realizadas em 17/10/2026 sobre a implementação da tarefa 3,
posteriormente registrada no commit `73a8c2a`. O commit da documentação da tarefa 4
é `a751e31`. Não se trata de uma nova execução da suíte para esta atualização textual.

| Ambiente | Comando / configuração | Testes | Falhas | Erros | Ignorados |
| --- | --- | ---: | ---: | ---: | ---: |
| H2 | `mvn -B test` | 127 | 0 | 0 | 0 |
| PostgreSQL 17.9 | `mvn -B test` com propriedades `sonarfy.test.jdbc-url`, `sonarfy.test.db-user` e `sonarfy.test.db-password` de banco descartável | 127 | 0 | 0 | 0 |

Os logs locais consultados foram `target/task3-h2-tests.log` e
`target/task3-postgres-tests.log`, com `BUILD SUCCESS`. Não estão versionados;
este documento preserva o resumo, não substitui os relatórios completos de uma
nova execução pelo avaliador.

| Cenário | Resultado observado nas execuções | Evidência automatizada |
| --- | --- | --- |
| Solicitação pública para conta inexistente, não verificada, elegível, em cooldown ou com falha SMTP | Resposta genérica uniforme; transporte chamado somente quando autorizado | ForgotPasswordFlowTest |
| Duas solicitações simultâneas de envio | Uma reserva e uma tentativa de entrega | EmailReservationIntegrationTest |
| Falha de entrega | Reserva mantida e nova tentativa imediata recusada | EmailReservationIntegrationTest |
| Cadastro desfeito por rollback | Nenhum envio de ativação | EmailReservationIntegrationTest |
| Atualização concorrente de perfil | Horário reservado preservado | EmailReservationIntegrationTest |
| Reutilização sequencial com outra senha nova | Segunda alteração rejeitada | PasswordResetIntegrationTest |
| Mesmo token em solicitações concorrentes de troca/recuperação | Exatamente uma alteração confirmada | PasswordResetIntegrationTest |
| Senha atual incorreta, identidade diferente ou confirmação divergente | Alteração rejeitada; token válido pode ser tentado novamente | PasswordResetIntegrationTest |
| Rollback após atualizar senha | Senha e corte temporal anteriores preservados | PasswordResetIntegrationTest |
| Token sem emissão, expirado, de outro propósito ou anterior/igual ao corte | Rejeitado | PasswordResetIntegrationTest |
| Precisão do JWT | Emissão serializada em segundos inteiros pela java-jwt 4.4.0 | JwtServiceTest |

A proteção é avaliada com transações e banco reais nos testes de integração.
O transporte de e-mail é simulado. Testes de controller com principal simulado
não demonstram autenticação HTTP real nem entrega SMTP.

## Roteiro de demonstração

1. Apresentar os três contratos principais: solicitar recuperação, redefinir senha
   e trocar senha autenticada. Mostrar por que a recuperação não pede senha atual.
2. Executar a suíte e mostrar total, falhas, erros e testes ignorados.
3. Mostrar `ForgotPasswordFlowTest`: a resposta pública não confirma existência
   da conta, e os cenários elegíveis realmente tentam enviar.
4. Mostrar `EmailReservationIntegrationTest`: cadastro confirmado antes do envio,
   rollback sem ativação e disputa por uma única reserva.
5. Mostrar `PasswordResetIntegrationTest`: reutilização com senhas diferentes,
   concorrência, rollback e rejeição no mesmo segundo.
6. Apresentar a coleção Postman como contrato de requisições e explicar a pendência
   de autenticação HTTP nas rotas protegidas.

Uma demonstração manual de cadastro, ativação e recuperação exige configuração
local de banco/schema, segredo de assinatura e transporte SMTP de teste.
Essas configurações pessoais não são distribuídas neste repositório.
A execução completa da aplicação ainda não possui um provisionamento reproduzível
documentado; o roteiro acima é reproduzível pela suíte automatizada.

Não apresentar a troca autenticada como fluxo HTTP completo: a aplicação ainda
não configura um mecanismo que estabeleça o principal. Essa pendência não foi
resolvida pela documentação.

## Decisões e limites

- DTOs separados preservam os campos obrigatórios de cada operação; a lógica
  compartilhada permanece no serviço.
- A reserva é atômica no banco e confirmada antes do SMTP. Falha de entrega mantém
  o cooldown compartilhado entre ativação e recuperação.
- Eventos após commit evitam enviar ativação de cadastro desfeito. São eventos em
  memória, síncronos, sem garantia de entrega após reinício.
- O bloqueio do usuário protege validação e atualização da senha na mesma transação.
  O corte temporal rejeita igualdade, pressupõe relógios coerentes e não revoga
  sessões de login.
- Respostas síncronas podem ter diferenças de duração; não se afirma eliminação
  completa da possibilidade de enumeração.
- Publicação para avaliação não significa aplicação pronta para produção.

A finalização do PR e eventuais exigências específicas de apresentação ou gravação
da instituição permanecem etapas externas a este guia.

