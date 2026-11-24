# Desativação de conta — preparação interna

Esta primeira entrega prepara apenas persistência e tokens. **Nenhuma rota de
desativação está disponível**, nenhum e-mail é enviado e nenhum estado de conta
ou sessão é alterado. As coleções HTTP existentes não mudam nesta etapa.

A divisão aprovada é:

1. Persistência da solicitação, token e migration.
2. Solicitação HTTP autenticada com senha atual, envio transacional ao e-mail
   verificado e controle de reenvio.
3. Confirmação explícita, consumo da autorização, `active=false` e revogação das
   sessões na mesma transação, preservando dados e relações.

O fluxo completo deve continuar indisponível até a confirmação estar pronta.
O endpoint de reativação existente continua sendo explícito; o login normal não
reativa uma conta inativa automaticamente.

## Contrato interno da persistência

`AccountDeactivationRequestRepository` exige uma transação externa em todas as
operações. Não representa uma autorização HTTP nem valida senha ou elegibilidade.
Os futuros serviços deverão autenticar o ator, conferir a senha e revalidar o
estado da conta sob bloqueio antes de criar uma solicitação. O ID nunca poderá
ser escolhido pelo cliente como forma de autorizar a operação.

- `replace(userId)` bloqueia o usuário e substitui sua solicitação atual.
  Gera um segredo de 256 bits com `SecureRandom`, prefixo `deactivation.` e corpo
  Base64 URL sem padding. Persiste apenas SHA-256 do token completo, um UUID da
  solicitação, emissão, expiração de quinze minutos e consumo inicialmente nulo.
- O resultado `Issued` contém o token para o futuro remetente e oculta o segredo
  em `toString()`. Não logar o accessor, serializar o resultado em respostas ou
  persistir o token bruto. O prefixo diferencia este formato dos demais tokens.
- `consume(token)` identifica o usuário pelo hash, bloqueia sua linha e relê a
  autorização por uma atualização condicional. Retorna somente o ID autorizado;
  token malformado, adulterado, substituído, futuro, expirado ou consumido não
  retorna ID. A expiração é exclusiva: na igualdade o token já é inválido.
- `invalidate(userId)` remove a solicitação somente dessa conta. Está preparado
  para integração futura com alterações de senha/e-mail e outras transições,
  mas esses fluxos ainda não chamam essa operação nesta entrega.

Emissão e expiração usam `statement_timestamp()` do PostgreSQL. A conferência
após aguardar o bloqueio usa uma nova instrução e não o horário congelado no
início da transação. Não depende do relógio do processo Java.

## Transações e integração futura

O bloqueio do usuário vem antes de alterar a solicitação, compatível com sessões
e relacionamentos. A consulta inicial por hash não bloqueia a solicitação; o
hash é revalidado depois do bloqueio do usuário, evitando consumir a autorização
antiga se ocorrer substituição concorrente. Operações envolvendo vários usuários
deverão manter a ordem crescente de ID já adotada no projeto.

Na próxima entrega, senha, elegibilidade, criação/substituição e enfileiramento
de e-mail deverão compartilhar a transação. Na confirmação futura, consumo,
revalidação da conta, desativação e revogação deverão confirmar ou sofrer rollback
juntos. O consumo isolado do repository não desativa a conta.

O hash não permite recuperar o segredo para uma retentativa de envio. Na segunda
entrega, o remetente deverá emitir ou rotacionar o token da solicitação ainda
vigente antes do SMTP, sem estender sua expiração, e reservar essa tentativa na
transação apropriada. Uma entrega de resultado incerto poderá exigir um novo
link; essa consequência deverá constar da integração. `replace` cria outra
solicitação e não deve ser usado como retentativa automática que renova o prazo.
A reserva/rotação de entrega ainda não está implementada nesta etapa.

A confirmação futura também deverá recusar autorizações anteriores a mudanças
de senha/e-mail ou ciclos de desativação/reativação, integrando a invalidação sob
o mesmo bloqueio. Não disponibilizar tokens externos antes dessa integração.

Não há mudança em autenticação, perfis, contadores, Spotify, arquivos ou política
de retenção nesta etapa. Expurgo de solicitações expiradas não foi introduzido.

Operação e validação: [integração de desativação](../integrations/account-deactivation.md).
