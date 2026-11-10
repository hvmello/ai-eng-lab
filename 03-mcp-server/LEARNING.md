# LEARNING: 03-mcp-server

## O que foi construído
Um servidor MCP somente leitura, em Java, sobre Streamable HTTP, com três tools (`list_documents`, `search_documents`,
`read_document`) e leitura paginada. Ele é ao mesmo tempo servidor de autorização OAuth, recurso protegido e servidor
MCP, tudo numa aplicação Spring Boot sobre PostgreSQL. Nenhum modelo é chamado em lugar nenhum, e o servidor vem
desligado por padrão.

## Conceitos de AI engineering
- **MCP é um protocolo entre cliente e servidor, e o modelo só aparece no meio.** O servidor expõe tools; o cliente
  (Claude Code, por exemplo) decide o que mostrar ao modelo. Por isso as fronteiras de confiança são três: usuário →
  cliente, cliente → servidor, e tudo que o servidor devolve → contexto do modelo.
- **Descrição de tool e resultado de tool são texto que entra no contexto do modelo.** Aqui as descrições são
  constantes estáticas, e o conteúdo dos documentos só sai no resultado, tratado como dado (a descrição de
  `read_document` diz isso). Um teste garante que nenhuma descrição contém texto de documento.
- **Descoberta de OAuth em MCP**: o cliente faz uma chamada sem token, recebe `401` com `WWW-Authenticate:
  Bearer resource_metadata="..."` (RFC 9728), lê de lá qual servidor de autorização usar (RFC 8414), registra-se
  sozinho (RFC 7591, registro dinâmico) e faz authorization code com PKCE. O parâmetro `resource` (RFC 8707) amarra o
  token ao servidor para o qual ele foi pedido.
- **Cliente público**: não guarda segredo. A prova de posse do código é o PKCE (`S256`), e a defesa contra token
  copiado é o token de acesso curto (5 min) mais refresh token que **rotaciona** a cada uso.
- **Detecção de replay**: guardamos o SHA-256 de cada refresh token aposentado. Se um aparece de novo, ou o cliente
  legítimo ou um ladrão tem uma cópia, e o servidor não sabe qual, então revoga a família inteira.
- **Cursor opaco e assinado**: base64url do payload mais HMAC. O payload diz qual tool emitiu e a que consulta ou
  documento ele está amarrado, então um cursor de outra tool, de outra busca ou editado vira erro tipado
  (`invalid_cursor`).

## Decisões e trade-offs
- **Componentes do Spring em vez de código próprio**: servidor de autorização, resource server, metadados RFC 9728 e
  transporte MCP são do framework. O código próprio cobre só as lacunas, listadas no ADR 0003 (registro aberto,
  replay de refresh, trava de linha, refresh e revogação para cliente público, `localhost` com porta variável,
  parâmetro `resource`).
- **Registro dinâmico próprio**: o endpoint do Spring exige um token inicial que um cliente MCP não tem, e a
  biblioteca da comunidade ainda é pré-1.0. Ficou um controller pequeno, com as regras numa classe testada à parte.
- **Token opaco, não JWT**: revogar um JWT antes de expirar exige lista de bloqueio ou introspecção, que é justamente
  o que o token opaco já dá. Com ele, "revogou" significa "a próxima chamada falha". O custo é uma consulta ao banco
  por requisição.
- **Modo stateless do Streamable HTTP**: cada requisição carrega o próprio token, então não há sessão no servidor para
  vazar ou sincronizar. Não temos notificações do servidor para o cliente, que não precisamos.
- **Spring deixa o código de autorização válido depois de um verifier errado**: só o reuso do código revoga. Não mudei
  isso; com um verifier de 256 bits, adivinhar não é viável, e o código dura poucos minutos.
- **Sem hash de token em repouso**: o repositório JDBC do Spring guarda o valor em texto. Aceitável num laboratório,
  é uma lacuna de produção.
- **Validação de `Origin` dentro do handler MCP** (a do SDK), não num filtro antes da autenticação. Uma requisição sem
  token e com `Origin` ruim recebe `401`, não `403`.

## Como rodar e testar
Veja o `README.md` do módulo. `./mvnw verify` roda 55 testes unitários e 42 de integração; os de integração precisam
de Docker (Testcontainers com PostgreSQL descartável). O que os testes provam e o que ainda precisa de validação
manual por cliente está separado numa tabela no README. **Claude Code ainda não foi validado de verdade.**

## Armadilhas comuns em produção
- **Objeto `User` compartilhado**: o Spring apaga a senha do usuário autenticado (`CredentialsContainer`). Devolver
  sempre a mesma instância no `UserDetailsService` faz o segundo login falhar. Apareceu nos testes.
- **Refresh paralelo sem trava**: o repositório JDBC lê e depois grava, sem compare-and-set. Sem a trava de linha,
  8 refreshes simultâneos com o mesmo token tiveram 8 sucessos (o teste falha sem a trava, o que prova o teste).
- **Nome de parâmetro**: sem `-parameters` no compilador, o schema da tool sai com `arg0`, `arg1`, e a validação do
  SDK rejeita chamadas corretas.
- **`redirect_uri` com `localhost`**: o Spring só aceita porta variável para IP literal. Clientes como o Claude Code
  usam `localhost` com porta aleatória.
- **IP atrás de proxy**: o limite por IP usa o endereço do TCP. Atrás de um proxy todos os clientes pareceriam um só.
- **Filtro de rede local**: numa máquina Windows com driver de filtro, respostas pequenas em loopback chegaram
  reescritas. Os testes passaram intactos dentro de um contêiner Linux. Não afeta o CI.

## Perguntas para o Heitor
1. O que uma descrição de tool maliciosa pode fazer com um cliente? Pense em quem lê a descrição, e quando.
2. Por que checar a autorização a cada leitura se o token ainda é válido? O que muda numa leitura paginada?
3. O que fica no histórico do cliente depois da revogação, e o que isso significa para promessas de privacidade?
4. Como autorização num servidor MCP difere de numa API REST comum? Considere quem é o chamador e quem decide o que
   ele pode fazer.
5. Deixar o código válido depois de um verifier errado é aceitável aqui? O que mudaria sua resposta?

## Para ir além
- Especificação MCP, autorização: https://modelcontextprotocol.io/specification/2025-06-18/basic/authorization
- RFC 9728 (Protected Resource Metadata): https://www.rfc-editor.org/rfc/rfc9728
- RFC 8414 (Authorization Server Metadata): https://www.rfc-editor.org/rfc/rfc8414
- RFC 7591 (Dynamic Client Registration): https://www.rfc-editor.org/rfc/rfc7591
- RFC 8707 (Resource Indicators): https://www.rfc-editor.org/rfc/rfc8707
- RFC 7636 (PKCE): https://www.rfc-editor.org/rfc/rfc7636
- Spring Authorization Server: https://docs.spring.io/spring-authorization-server/reference/
- Spring AI, servidores MCP: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html
