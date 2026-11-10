# 1. LLM library

**Status:** Accepted

## Decision

LangChain4j is the default. Module 02 also implements its tools with Spring AI, to compare the two.

## Why

LangChain4j does not tie the code to a Spring release, and it has first-class Ollama and Anthropic modules.
Spring AI is the natural alternative for Spring teams, so a direct comparison is more useful than choosing
by reputation.

## Consequences

Application code depends on a small provider abstraction in `shared/`, not on either library. Only
`LangChain4jLlmClient` imports `dev.langchain4j`, and a test checks that module 01 does not.

**Structured output from Claude (answered in module 01, LangChain4j 1.23.0).** Yes. `AnthropicChatModel` accepts a
JSON-schema `ResponseFormat`, and the adapter sends it as `output_config.format` with `type: json_schema` and the
schema. Evidence level: the request shape, checked against a local fake HTTP server (`WireLevelTest`), together with
a recorded-style response parsed back into text, finish reason and usage. **No live call to Anthropic was made**, so
how a real Claude model behaves under this schema is not verified here. The same module runs against Ollama
(`gpt-oss:20b`) and produced a valid typed invoice from the schema, by hand, which proves the Ollama path only.

The adapter leaves `temperature`, `top_p` and the other sampling fields null unless the caller sets them, and
LangChain4j then omits them from the request. That is what makes one request work on Claude models that reject
sampling parameters.
