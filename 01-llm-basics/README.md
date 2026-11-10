# 01-llm-basics

Chat, streaming and a typed extraction of an invoice into a validated Java record, on a local model through Ollama.
No API key is needed.

## Run

```
# Ollama must be running, with the model pulled: ollama pull gpt-oss:20b
./mvnw -B -q -pl 01-llm-basics -am install -DskipTests
./mvnw -B -q -pl 01-llm-basics exec:java -Dexec.args="stream Count from one to five"
```

The commands read `OLLAMA_BASE_URL` (default `http://localhost:11434`) and `OLLAMA_MODEL` (default `gpt-oss:20b`).
`-Dexec.args` takes one of:

| Command | What it does |
|---|---|
| `chat <prompt>` | One blocking call, prints the answer, finish reason and token usage |
| `stream <prompt>` | Prints tokens as they arrive |
| `extract [text]` | Extracts an `Invoice` from the text (a built-in sample when omitted) |

Tested model: `gpt-oss:20b` on Ollama. `extract` returned a valid record from the schema. A first call can take over a
minute while the model loads.

## Tests

`./mvnw verify` from the repository root. No test calls a real model or needs Ollama or a key. The adapter is tested
against stub LangChain4j models, and at wire level against a local fake HTTP server that replays recorded responses.

## How extraction fails

`call model -> check finish reason -> parse with Jackson (strict) -> validate values`. Each stage has its own typed
failure, and every one keeps the raw model output:

| Exception | Meaning |
|---|---|
| `TruncatedOutput` | The model hit the output limit |
| `MalformedJson` | Empty, or not JSON |
| `SchemaMismatch` | JSON with the wrong shape: missing or null field, unknown field, wrong type, bad date |
| `InvalidValue` | Right shape, broken rule: non-positive amount, bad currency, line items that do not sum to the total |

Provider and transport failures are a different type, `LlmCallException`. There are no retries in this module.

Limit: a value that is wrong but internally consistent passes validation. A test pins this on purpose.

## Ollama and Claude

Statements about Claude checked on 2026-11-10 against the Anthropic documentation
([models overview](https://platform.claude.com/docs/en/about-claude/models/overview),
[Sonnet 5.5 migration guide](https://platform.claude.com/docs/en/models/sonnet-5-5/migration-guide)). Hosted models
change, so re-check before relying on them.

| | Ollama (local) | Claude (hosted) |
|---|---|---|
| Authentication and cost | None, runs on your machine, free | API key, paid per token |
| `temperature` | Accepted | Rejected by current models: the migration guide says to remove `temperature`, `top_p` and `top_k`. `ChatParameters` leaves null fields out of the request for this reason |
| Structured output | `format` field with the JSON schema | `output_config.format` with `type: json_schema` (request shape verified against a fake server; no live call made) |
| Streaming | Newline-delimited JSON | Server-sent events |
| Token accounting | `prompt_eval_count` and `eval_count` | `usage.input_tokens` and `usage.output_tokens` |
| System prompt | A message with the system role | A separate top-level `system` field; the adapter maps the system message to it |
| Output limit | Optional (`num_predict`) | `max_tokens` is required; LangChain4j sends 1024 when unset |
| Model IDs | `gpt-oss:20b` here | `claude-sonnet-5-5`, `claude-opus-5-5`, `claude-haiku-5-5` |

## Layout

- `shared/` (one level up): `LlmClient`, request and result types, `LlmClients.ollama(...)` and
  `LlmClients.anthropic(...)`. Only this module imports LangChain4j (ADR 0001).
- `src/main/resources/invoice.schema.json`: the schema sent to the model. A test checks it against the `Invoice` record.
