# LEARNING: 04-agent-guardrails

## O que foi construído
Um agente com loop de ferramentas limitado em código: passos, chamadas de ferramenta e orçamento de tokens ou custo
encerram a execução, toda ferramenta declara uma permissão (`READ` ou `WRITE`), e uma escrita só roda depois de uma
aprovação explícita. O domínio é um brinquedo (notas em memória), de propósito. Os testes de injeção de prompt
reexecutam turnos gravados do modelo contra o código de guarda de verdade, sem chamar modelo nenhum.

## Conceitos de AI engineering
- **Agente versus fluxo fixo**: um agente deixa o modelo decidir quantos passos dar e quais ferramentas chamar. Isso
  só se justifica quando o caminho não é conhecido de antemão. Se você consegue escrever o fluxo como código (passo A,
  depois B, depois C), o fluxo fixo é mais barato, mais previsível e mais fácil de testar. Autonomia é custo, não
  vantagem.
- **Defesa em profundidade, com o portão no código**: o prompt de sistema diz "uma escrita precisa de aprovação", mas
  nada depende de o modelo obedecer. O `Approver` roda depois da validação e antes da execução, e só vê um resumo que a
  aplicação montou a partir de argumentos já tipados. Texto do modelo nunca chega ao portão como se fosse decisão.
- **Permissão por ferramenta, sem padrão**: não existe ferramenta sem permissão declarada. Esquecer de declarar é
  erro de compilação ou de registro, não uma escrita liberada por engano.
- **Medição de custo depois do fato**: o uso de tokens só é conhecido depois da chamada. Uma chamada pode estourar o
  orçamento. Mitigamos o lado da saída com `maxOutputTokens = min(teto, o que sobrou)`, mas o lado da entrada não tem
  limite. O limite é uma cota, não uma linha exata. E se o provedor não informar o uso, a execução para
  (`USAGE_UNREPORTED`): orçamento que não se mede não é orçamento.
- **Fadiga de aprovação**: um humano que aprova tudo sem ler anula o portão. Por isso o resumo é curto e sem
  sequências de controle (um texto numa nota poderia limpar a tela e desenhar um "APROVADO" falso), e o número de
  perguntas por execução é limitado.
- **Testar o que não é determinístico**: não se testa o modelo, testa-se o código em volta dele. Gravamos os turnos do
  modelo (texto, chamadas, uso) e reexecutamos contra o loop real, afirmando invariantes ("a nota não mudou", "o
  aprovador foi consultado uma vez") e não saídas exatas. O teste também falha se sobrar turno gravado, para que uma
  gravação velha não passe calada.

## Decisões e trade-offs
- **Um segundo loop em vez de reaproveitar o `ToolLoop` do módulo 02**: ele é `final` e não tem ponto de entrada para um
  portão de permissão. Reaproveitamos as peças (`Tool`, `Arguments`, `ToolResult`, `ToolFailure`) e duplicamos o loop.
  O custo é a duplicação (retry, circuit breaker e timeout existem nos dois). Extrair um loop compartilhado fica para
  depois, e até lá uma mudança em `Tool` no 02 quebra o 04.
- **Um valor novo em `ToolResult.ErrorType` (`approval_denied`) no módulo 02**: aditivo, não muda comportamento algum do 02.
- **Fixtures sintéticas**: escritas à mão no formato da gravação e marcadas `"source": "synthetic"`. Elas provam o
  código de guarda, não provam que um modelo real produz esses turnos nem a taxa com que ele cai em injeção. Regravar
  com um modelo real é um passo manual (`--record`), fora do CI.
- **Falha fechada**: erro no resumo ou no aprovador vira negação. Sem console, negação. Sem uso informado, parada.
- **Sem canal de saída**: não há ferramenta de rede ou shell, então uma instrução injetada não tem como vazar dado.
  Isso é uma escolha de domínio, e é a defesa mais barata que existe.
- **Fora do escopo**: prazo de relógio por execução, persistência das notas, aprovação assíncrona (web, Slack),
  classificador de injeção baseado em LLM, provedores hospedados além do que o `LlmClients` já oferece.

## Como rodar e testar
`./mvnw -pl 04-agent-guardrails -am verify` roda tudo sem rede e sem modelo. Para ver o agente de verdade, use o Ollama:
`./mvnw -q -pl 04-agent-guardrails exec:java -Dexec.args='ask "o que tem na nota shopping?" --approve prompt'`.
Para criar um caso de injeção novo, grave uma execução com `--record <arquivo>` e siga o README do módulo.

## Armadilhas comuns em produção
- Confiar no prompt para impedir uma ação perigosa. Prompt é pedido, código é regra.
- Resumo de aprovação que mostra texto cru do modelo ou de uma ferramenta: dá para forjar a pergunta.
- Aprovação que o modelo consegue pedir de novo até cansar o humano. Limite as perguntas e lembre da negação.
- Limite de custo que só olha o total depois que o dinheiro foi gasto, sem teto por chamada.
- Contar tokens com estimativa quando o provedor não informa. Melhor parar.
- Reaproveitar o resultado de uma ferramenta como se fosse instrução: ele é dado, sempre.

## Perguntas para o Heitor
1. Quais das suas tarefas realmente precisam de um agente em vez de um fluxo fixo? Se você consegue listar os passos de
   antemão, o fluxo fixo costuma ser a resposta melhor.
2. Qual é a pior ação que este agente pode fazer, e o que a impede? (Resposta do módulo: sobrescrever uma nota; o que
   impede é a aprovação em código, a validação do nome, o limite de tamanho e a ausência de ferramenta de rede. Você
   concorda que isso cobre o caso, ou há uma ação pior que eu não considerei?)
3. Como você testa comportamento que não é determinístico? Aqui foi com turnos gravados mais invariantes; em que ponto
   você acha que isso deixa de bastar e precisa de uma avaliação com modelo real?
4. Quando o Ollama não informa o uso de tokens, a execução para por padrão. Você prefere esse comportamento ou uma
   opção explícita `--allow-unmetered` para modelos locais?

## Para ir além
- OWASP Top 10 for LLM Applications (Prompt Injection, Excessive Agency): https://genai.owasp.org/llm-top-10/
- Anthropic, "Building effective agents": https://www.anthropic.com/engineering/building-effective-agents
- Simon Willison, "The lethal trifecta for AI agents": https://simonwillison.net/2025/Jun/16/the-lethal-trifecta/
- Documentação de tool use da Anthropic: https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/overview
