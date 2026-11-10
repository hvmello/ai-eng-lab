# 02-tool-calling

A model calls Java tools against a public API (Open-Meteo, no key), and the application stays in control of the loop:
it validates every call, runs the tool under a timeout, sends back a structured result, and bounds the whole run.
Runs on a local model through Ollama, with LangChain4j (default) or Spring AI behind the same `LlmClient`.

## Run

```
# Ollama must be running, with the model pulled: ollama pull gpt-oss:20b
./mvnw -B -q -pl 02-tool-calling -am install -DskipTests
./mvnw -B -q -pl 02-tool-calling exec:java -Dexec.args="ask Will it rain in Porto Alegre tomorrow?"
./mvnw -B -q -pl 02-tool-calling exec:java -Dexec.args="ask Will it rain in Porto Alegre tomorrow? --lib springai"
```

`--lib` is `langchain4j` (default) or `springai`. Configuration comes from `OLLAMA_BASE_URL` (default
`http://localhost:11434`) and `OLLAMA_MODEL` (default `gpt-oss:20b`, which supports tools).

The output is one trace line per tool call, then the answer:

```
[step 1] geocode_city -> ok
[step 2] get_forecast -> ok
<answer>
```

The trace has the tool and its status only. Arguments and upstream data are never printed.

This is the one manual step: it calls the public Open-Meteo APIs, which are free for non-commercial use and rate
limited. No test and no CI step calls them.

## The loop

`call model -> for each tool call: resolve, validate, execute -> send results -> repeat`

| Outcome | Meaning |
|---|---|
| `Answered` | The model answered in text |
| `StepLimitReached` | The model was still asking for tools after `maxSteps` model calls |
| `Truncated` | The model output was cut off (`LENGTH`); nothing it said is trusted |

A model failure (`LlmCallException`) propagates after one call. The loop never retries a model call.

### Limits (`LoopLimits`, defaults 6, 4, 2, 10 s)

| Limit | Effect |
|---|---|
| `maxSteps` | Model calls per run |
| `maxToolCallsPerStep` | Calls beyond it get a structured, retryable error and are not executed |
| `maxFailuresPerTool` | After this many failures a tool answers `tool_unavailable` and is not executed again in the run |
| `toolTimeout` | A tool that is too slow gets a retryable `tool_error` |

An identical call (same tool, same arguments after canonicalisation) that already failed with a non-retryable error
is answered from the record, without running.

## What the model sees

Every tool result is one JSON envelope:

```
{"ok":true,"data":{...}}
{"ok":false,"error":{"type":"...","message":"...","retryable":true,"fields":[{"path":"latitude","problem":"..."}]}}
```

Types: `unknown_tool`, `invalid_arguments`, `tool_error`, `tool_unavailable`. The message says what the model can
change. It never carries stack traces, exception class names, URLs, hosts, headers or raw upstream bodies.

The application validates arguments (`Arguments`, strict Jackson plus checks in the record constructors). The schema
sent to the model is a hint, not a guarantee.

## Tools

| Tool | Arguments | Returns |
|---|---|---|
| `geocode_city` | `name`, optional `count` (1 to 10) | `matches`: name, region, country, latitude, longitude |
| `get_forecast` | `latitude`, `longitude`, optional `days` (1 to 7) | `days`: date, min and max temperature, precipitation and its probability |

Only those fields are returned; the rest of the Open-Meteo answer is dropped. HTTP 4xx is a non-retryable
`tool_error`, except 429, which is retryable like 5xx and timeouts.

Tool output is untrusted input to the model. Defences against injection through tool results belong to module 04.

## Tests

`./mvnw verify` from the repository root. No test calls a real model, Ollama, or the Open-Meteo service.

- `ToolLoopTest`: scripted stub model and fake tools (chains, unknown tool, invalid arguments, errors, circuit breaker,
  step and per-step limits, timeout, truncation).
- `ToolResultTest`: the envelope as golden strings.
- `OpenMeteoToolsTest`: a local fake HTTP server (parsing, encoding, 4xx, 5xx, timeout, malformed and oversized bodies).
- `SpringAiLlmClientTest`: a fake Ollama server; checks that tool calls come back and no callback runs.
- `ArchitectureTest`, `MainTest`.

See [ADR 0002](../docs/adr/0002-tool-calling-spring-ai-vs-langchain4j.md) for the library comparison.
