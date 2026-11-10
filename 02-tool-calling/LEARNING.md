# LEARNING: 02-tool-calling

## O que foi construído
Um loop de tool calling que pertence à aplicação: o modelo só pede uma ferramenta, e o nosso código resolve a chamada,
valida os argumentos, executa com timeout e devolve um resultado estruturado. Duas ferramentas Java chamam a API pública
Open-Meteo (`geocode_city` e `get_forecast`), e o mesmo loop roda sobre LangChain4j e sobre Spring AI, cada um atrás
da interface `LlmClient`.

## Conceitos de AI engineering
- **O modelo não executa nada**: ele devolve "quero chamar a ferramenta X com estes argumentos". Quem executa, valida e
  decide quando parar é a aplicação. Isso é o que mantém o controle com você.
- **O schema é uma dica, não uma garantia**: o schema vai na requisição para orientar o modelo, mas ele pode mandar
  campo faltando, tipo errado, campo extra ou até um JSON quebrado. Os argumentos são entrada não confiável, como o
  corpo de um POST de um cliente externo.
- **Erro de ferramenta é conteúdo para o modelo**: quando a chamada é inválida ou a ferramenta falha, o resultado volta
  como JSON estruturado (`ok:false`, tipo, mensagem, `retryable`, campos com problema). Assim o modelo pode corrigir
  os argumentos ou desistir, em vez de a aplicação quebrar.
- **Ids de chamada**: cada resultado precisa ser ligado à chamada que o originou. O Ollama não manda id, então o adapter
  gera um (`call_1`, ...); a Anthropic manda e exige o mesmo id de volta.
- **Loop limitado**: sem limite, um modelo que não para de pedir ferramentas vira custo e latência sem fim. Aqui há
  limite de passos, de chamadas por passo, de falhas por ferramenta e de tempo por ferramenta.
- **Resultado de ferramenta é entrada não confiável para o modelo**: o texto que volta de uma API pode conter
  instruções ("ignore as instruções anteriores"). Por isso devolvemos só os campos que o modelo precisa, e o prompt de
  sistema diz que resultados são dados, não instruções. Testes de injeção ficam para o módulo 04.

## Decisões e trade-offs
- **Loop próprio em vez do loop da biblioteca**: `AiServices` (LangChain4j) e o `ChatClient` (Spring AI) podem rodar o
  loop por você, mas aí validação, formato de erro e limites ficam nos defaults deles. Com o loop nosso, tudo é Java
  simples e testável com um modelo falso. O custo é escrever o loop. Não avaliei esses recursos das bibliotecas.
- **Circuit breaker por execução**: depois de `maxFailuresPerTool` falhas a ferramenta responde `tool_unavailable` e
  não roda mais naquela execução. Uma chamada idêntica que já falhou sem esperança também não roda de novo. O limite de
  passos é a última barreira. Falha de validação conta como falha, porque um modelo que erra os argumentos em loop é
  uma tempestade de retries do mesmo jeito.
- **429 é retentável, outros 4xx não**: a regra "4xx não adianta repetir" não vale para limite de taxa.
- **Spring AI 2.0.2 não tem mais o interruptor de execução interna**: o `OllamaChatModel.call` só devolve as chamadas.
  O adapter passa callbacks que lançam exceção se forem chamados, e um teste prova que nenhum roda.
- **Spring AI traz peso**: Spring Framework 7, Reactor, Micrometer e Jackson 3 ao lado do Jackson 2. Só o módulo 02 paga.
- **Sem streaming com ferramentas e sem chamada ao vivo a modelo hospedado**: fora de escopo; o ADR 0002 registra o
  nível de evidência.

## Como rodar e testar
- Testes: `./mvnw verify` na raiz. Nenhum teste chama modelo real, Ollama ou a Open-Meteo.
- Manual: com o Ollama rodando e `gpt-oss:20b` baixado, `ask "Vai chover em Porto Alegre amanhã?"` com `--lib
  langchain4j` e com `--lib springai`. Isso chama a Open-Meteo de verdade. Detalhes no `README.md` do módulo.

## Armadilhas comuns em produção
- Confiar no schema e pular a validação em Java.
- Devolver ao modelo a mensagem da exceção: ela vaza classe, host, URL, token ou corpo da resposta de terceiros.
- Retry sem limite e sem memória: o modelo repete a mesma chamada que já falhou, e você paga cada volta.
- Deixar a biblioteca rodar o loop e nunca saber qual é o limite real de passos.
- Ferramenta sem timeout: uma API lenta segura a execução inteira.
- Tratar `finish_reason = length` como resposta completa.
- Resposta de API repassada inteira para o modelo: mais tokens, mais superfície para injeção.

## Perguntas para o Heitor
1. Quem valida os argumentos de uma ferramenta, o modelo ou a aplicação, e por quê?
2. O que um erro de ferramenta deve dizer ao modelo, e o que deve esconder?
3. Como você impediria uma tempestade de retries causada por uma ferramenta que continua falhando?

## Para ir além
- [Tool calling no Ollama](https://docs.ollama.com/capabilities/tool-calling)
- [Tool use na API da Anthropic](https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview)
- [LangChain4j: ferramentas](https://docs.langchain4j.dev/tutorials/tools)
- [Spring AI: tool calling](https://docs.spring.io/spring-ai/reference/api/tools.html)
- [Open-Meteo](https://open-meteo.com/en/docs)
