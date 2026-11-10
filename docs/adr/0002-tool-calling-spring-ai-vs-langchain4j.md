# 2. Tool calling: Spring AI vs LangChain4j

**Status:** Accepted

## Decision

LangChain4j stays the default adapter. The application owns the tool loop (`ToolLoop` in module 02), and both
libraries only translate: tool specifications go out, tool calls come back, and neither runs a tool. Spring AI is a
second adapter behind the same `LlmClient`, used with `--lib springai`.

## Why

Validation, error shaping and limits are the parts that decide whether a tool loop is safe. If a library runs the loop,
those decisions move into its defaults and become hard to test. With an app-owned loop they are plain Java, tested
with a scripted stub model, and identical for both libraries. Comparing the libraries as adapters under that one loop
makes the comparison concrete.

## What was compared, from what was built

Versions: LangChain4j 1.23.0, Spring AI 2.0.2 (the latest stable on Maven Central on 2026-11-10; `2.1.0-RC2` is a
release candidate and was not used).

| | LangChain4j 1.23.0 | Spring AI 2.0.2 |
|---|---|---|
| Declaring a tool | `ToolSpecification` with a `JsonObjectSchema`. A raw JSON schema string is not accepted as parameters, so `shared/` converts the subset tools need (`JsonSchemas`) | `ToolCallback` with a `ToolDefinition` that takes the schema as a string, so it is passed as is |
| Who runs the tool | Nobody, when the model is called directly with specifications: the answer carries `toolExecutionRequests` | Nobody in `OllamaChatModel.call` in this version: it returns the tool calls. The 1.x `internalToolExecutionEnabled` switch no longer exists on `OllamaChatOptions`. A callback that throws if called is passed anyway, and a test proves no callback runs and only one request is sent |
| Tool call ids | Ollama sends none; the adapter makes `call_1`, `call_2`, ... so results can be paired with calls | Same: the adapter fills a missing id |
| Retry | `maxRetries(0)` on the blocking model | A `RetryTemplate` with `RetryPolicy.withMaxRetries(0)`; a test proves a 500 is one request |
| Setup without a framework | One builder per provider, no container | Hand-built `OllamaApi` (over `RestClient`) and `OllamaChatModel`, no Spring context. It works, but needs the timeout set on the request factory by hand |
| Dependency weight | `langchain4j-core`, the provider module and a JDK HTTP client | Spring Framework 7.0.10 (context, web, webflux, messaging), Reactor, Micrometer, and Jackson 3 (`tools.jackson`) next to the Jackson 2 the rest of the repository uses |
| Finish reason | Mapped from the provider's reason | Set only when the Ollama response carries token counts; a response without them gives no finish reason. Real Ollama responses carry them |
| Providers covered here | Ollama and Anthropic (wire tests with tools, `tool_use` and `tool_result`) | Ollama only |

Not evaluated: LangChain4j's `AiServices` and `@Tool` loop, Spring AI's `ChatClient` and its tool advisors, and any
native loop limits they offer. This repository does not use them, because the loop is ours. Spring AI 2.0.2 ships
`ToolCallLimits`, but it is package-private, so it cannot be configured from the adapter.

## Consequences

- `shared/` gains `ToolSpec`, `ToolCall`, a `TOOL` message role and `ChatRequest.tools`. Existing constructors and
  factories are kept, so module 01 compiles and passes unchanged.
- `stream` rejects a request with tools (`IllegalArgumentException`). Streaming with tools is out of scope.
- Only `SpringAiLlmClient` imports `org.springframework`, and nothing in module 02 imports `dev.langchain4j`;
  `ArchitectureTest` checks both.
- Adding Spring AI puts a Spring Framework line and Jackson 3 on module 02's classpath. Only that module pays for it.

## Evidence level

Request and response shapes are checked against local fake HTTP servers replaying recorded-style responses
(`WireLevelTest`-style tests in `shared/`, `SpringAiLlmClientTest` in module 02). The loop is tested with a stub model.
`ask` was also run by hand against `gpt-oss:20b` on Ollama with both libraries and answered a two-step question
(geocode, then forecast). **No live call to a hosted model was made**, so tool calling against Claude is verified at the
request-shape level only.
