# Integração de sessões de autenticação

O contrato HTTP atual está em [autenticação](../api/authentication.md).
Esta entrega prepara apenas a persistência da preferência lembrar-me, sem
alterar entradas, respostas, CORS ou cookies do login existente.

O serviço interno aceita `issue(user, rememberMe)`; a emissão sem preferência
usa `false`. A escolha fica persistida em cada token da sessão e é copiada na
rotação. `RotationDto.rememberMe()` fornece esse valor ao futuro adaptador HTTP;
não deve ser substituído por um parâmetro de refresh enviado pelo cliente.

Os dois modos mantêm 30 dias por inatividade e não têm prazo absoluto.
A futura integração HTTP usará a preferência para distinguir cookie de sessão
de cookie persistente. O frontend deverá coordenar renovações entre chamadas e
abas: reutilizar um token consumido encerra a sessão afetada.

Nenhum endpoint novo nem checkbox de frontend é entregue nesta etapa.
