# LEARNING: 01-llm-basics

## O que foi construído
Um cliente de LLM com três comandos (`chat`, `stream`, `extract`) sobre Ollama, e uma camada `shared/` que esconde o
LangChain4j atrás de uma interface própria. O `extract` transforma texto livre de nota fiscal em um record `Invoice`
validado, e cada tipo de falha tem sua própria exceção tipada.

## Conceitos de AI engineering
- **Requisição e resposta**: o modelo recebe mensagens com papéis (system, user, assistant) e devolve texto, um motivo
  de parada e a contagem de tokens. O motivo de parada importa: `LENGTH` significa saída cortada, e um JSON cortado
  pode até parecer válido.
- **Parâmetros**: `temperature` e `top_p` controlam amostragem. Os modelos Claude atuais rejeitam esses campos, então o
  nosso `ChatParameters` usa `null` para "não envie", em vez de um valor padrão.
- **Streaming**: os tokens chegam por callback. O contrato aqui é: exatamente um `onComplete` ou um `onError`, nunca os
  dois, mesmo se o provedor falhar no meio.
- **Saída estruturada**: o schema JSON vai na requisição, mas o modelo não é obrigado a respeitá-lo em todo provedor.
  Por isso o código parseia em modo estrito (campo desconhecido falha) e valida de novo.
- **Duas camadas de validação**: o schema garante o formato; as regras em Java garantem valores plausíveis (valores
  positivos, moeda ISO, itens somando o total).

## Decisões e trade-offs
- **Abstração própria em vez de usar o LangChain4j direto**: o código da aplicação não importa `dev.langchain4j`, então
  trocar de biblioteca (ou compará-la com Spring AI no módulo 02) mexe em um arquivo só. O custo é uma camada a mais.
- **Sem Bean Validation**: as regras são poucas e em Java simples. Uma dependência a menos, mas sem anotações.
- **`maxRetries(0)`**: nenhuma tentativa escondida. Quem quiser retry decide onde, e o código mostra.
- **Sem retry no módulo**: reenviar uma saída inválida custa tokens e pode repetir o erro; fica para quando houver
  uma política clara.
- **Chamada ao vivo na Anthropic não foi feita**: o formato da requisição foi verificado contra um servidor falso, não
  contra o Claude de verdade. O ADR 0001 diz isso.
- A soma dos itens é checada porque exercita o caso "JSON válido com valor errado". Um valor errado porém consistente
  (todos os preços multiplicados por dez, junto com o total) passa, e um teste documenta essa limitação.

## Como rodar e testar
- Testes: `./mvnw verify` na raiz. Nenhum teste chama modelo real nem precisa de Ollama ou chave.
- Manual: com o Ollama rodando e `gpt-oss:20b` baixado, rode a classe `Main` do módulo com `chat`, `stream` ou
  `extract`. Detalhes no `README.md` do módulo.

## Armadilhas comuns em produção
- Confiar no `finish_reason`: ignorá-lo deixa passar saída truncada.
- Tratar falha de transporte e saída ruim do modelo como a mesma coisa. Uma pede retry de rede, a outra não.
- Logar a saída bruta sem cuidado: ela pode conter dados do usuário.
- Modelo local com "pensamento" interno gasta tokens do limite de saída antes de responder.
- Parâmetros que um provedor aceita e outro rejeita quebram a troca de modelo só em produção.

## Perguntas para o Heitor
1. O que acontece na sua extração quando o modelo devolve um JSON válido com um valor errado?
2. Quando o streaming vale a complexidade extra em um backend?
3. Onde você colocaria retries, e o que eles nunca devem repetir?

## Para ir além
- [Visão geral dos modelos Claude](https://platform.claude.com/docs/en/about-claude/models/overview)
- [Guia de migração do Claude Sonnet 5.5](https://platform.claude.com/docs/en/models/sonnet-5-5/migration-guide)
- [LangChain4j](https://docs.langchain4j.dev/)
- [Ollama: saídas estruturadas](https://docs.ollama.com/capabilities/structured-outputs)
