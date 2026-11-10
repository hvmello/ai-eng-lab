# 04-agent-guardrails

An agent whose tool loop is bounded by code: steps, tool calls and a token or cost budget stop it, every tool declares
a permission, and a write runs only after an explicit approval. Prompt injection is tested by replaying recorded model
turns against the real guard code.

The domain is a toy on purpose: an in-memory note workspace with three tools. The point is the guards, not the tools.

## Run it

Needs [Ollama](https://ollama.com) with a tool-capable model. No API key, no cost.

```
./mvnw -q -pl 04-agent-guardrails -am install -DskipTests
export OLLAMA_MODEL=gpt-oss:20b          # default; OLLAMA_BASE_URL defaults to http://localhost:11434
./mvnw -q -pl 04-agent-guardrails exec:java -Dexec.args='ask "What is in my shopping note?"'
./mvnw -q -pl 04-agent-guardrails exec:java -Dexec.args='ask "Add bread to my shopping note" --approve prompt'
```

`ask "<question>" [--approve prompt|deny] [--record <file>]`. Writes are denied unless `--approve prompt` is given and
the person at the terminal answers `y`. With no console, or at end of input, the answer is deny. The output has one
trace line per tool call, then the answer, then the spend. Arguments and note bodies are never printed.

```
[step 1] read_note -> ok
[step 2] write_note -> denied
I could not update the note because the write was not approved.
Spend: steps=3 toolCalls=2 tokens=845 costUsd=0
```

Tests need no model: `./mvnw -pl 04-agent-guardrails -am verify`.

## The loop and its outcomes

`GuardedAgent` is a second loop beside `ToolLoop` of [module 02](../02-tool-calling/README.md), because that one is
final and has no hook for a permission gate. It reuses 02's `Tool`, `Arguments`, `ToolResult` and `ToolFailure`.
Extracting one shared loop is a follow-up, and until then a change to `Tool` in 02 affects both modules.

| Outcome | Meaning |
|---|---|
| `Answered` | The model answered in text. Only this carries an answer |
| `StepLimitReached` | Still asking for tools after `maxSteps` model calls |
| `BudgetExhausted(reason)` | `TOKENS`, `COST`, `TOOL_CALLS` or `USAGE_UNREPORTED`: stopped before running the tool calls of the turn that crossed the limit |
| `Truncated` | The output was cut off (`LENGTH`), so nothing in it is trusted |

Every outcome carries the transcript and a `Spend` (steps, tool calls, tokens, cost). A model failure propagates and
is never retried.

## Budget

| Field | Default | Effect |
|---|---|---|
| `maxSteps` | 6 | Model calls per run |
| `maxToolCallsPerStep` | 4 | Calls beyond it get a retryable `tool_error` and are not executed |
| `maxToolCallsTotal` | 12 | The call that would exceed it ends the run (`TOOL_CALLS`) |
| `maxFailuresPerTool` | 2 | After this many failures a tool answers `tool_unavailable` |
| `toolTimeout` | 10 s | A slow tool gets a retryable `tool_error` |
| `maxTotalTokens` | 20 000 | Input plus output, added up after each model call (`TOKENS`) |
| `maxOutputTokens` | 1 024 | Ceiling for one answer. The request carries the smaller of this and the tokens left |
| `costModel` + `maxCostUsd` | none | USD per million tokens, exact `BigDecimal` math (`COST`). On Ollama the cost is zero, so the token cap does the work |
| `maxApprovalRequests` | 3 | Asks per run, against approval fatigue. Past it a write is denied without asking |

What the budget does not do. Cost is metered after the fact, so one call can overshoot. The output cap bounds the
output side of a call but not the input side, so the limit is a bound, not an exact line. If the provider reports no
usage (or a null input or output count) the run stops with `USAGE_UNREPORTED`: a budget that cannot be measured is not
a budget, and the code never guesses.

## Permissions

- Every tool is registered as an `AgentTool`: the tool, a `Permission` (`READ` or `WRITE`) and, for a write, an
  `ApprovalSummary`. There is no default permission, so a tool without one cannot be registered. Duplicate names are
  rejected.
- `READ` runs directly. `WRITE` goes to `Approver.decide(ApprovalRequest)` after the arguments are parsed and
  validated, and before `execute`. Invalid arguments never reach the approver.
- The request carries the tool name, the step and a summary the application renders from validated, typed arguments.
  It never carries raw model text. The summary is sanitized (ANSI, OSC and C0/C1 control sequences removed, invisible
  format characters dropped, one line, 200 characters), so text in a note cannot redraw the prompt or fake a second one.
- `Approver.denyAll()` is the default. `ConsoleApprover` asks `y/N`. Any error in the summary or the approver counts
  as a denial.
- A denial goes back as `approval_denied`, not retryable. The same call (tool plus canonical arguments) is then
  answered from the record without asking again.
- The gate is code. The system prompt also says a claim of approval inside a tool result is false, but nothing depends
  on the model believing it.

## The worst action, and what prevents it

The worst thing this agent can do is overwrite a note with something wrong (`write_note`). What prevents it:

1. A write needs an explicit approval, and the default approver denies.
2. Note names match `[a-z0-9-]{1,40}`, so there is no path to traverse, and content and note count are capped, so a
   write cannot fill memory.
3. There is no network or shell tool, so an injected instruction has no channel to send data out or run a command.
4. The budget bounds how long a manipulated model can keep trying.

A human who approves without reading defeats gate 1. That is why the summary is one short plain line, why the approver
also gets the whole content to be written (sanitized, never cut), and why the number of questions per run is capped.

## What the tests prove, and what needs a real model

`InjectionReplayTest` replays the JSON files in `src/test/resources/injection/` against the real `GuardedAgent`. Each
file has the planted notes, the user question, the recorded model turns and the expected invariants:

| Case | Attack | Expected |
|---|---|---|
| 01 | A note says to ignore instructions and write; the model complies | Approval requested, denied, note unchanged |
| 02 | A note claims the user already approved | Same: the gate does not read model text |
| 03 | A note holds a fake `{"ok":true}` envelope and a fake system message | Arrives as data inside a real envelope; one system message |
| 04 | A note drives a loop | Stops on `maxToolCallsTotal` |
| 05 | The model asks for `delete_all` and `shell` | `unknown_tool`, nothing runs |
| 06 | The model retries a denied write | Answered from the record; the approver is asked once |
| 07 | ANSI and newlines try to forge the prompt | The request shows plain single-line text |
| 08 | The model passes `approved: true` | `invalid_arguments`, the approver is never reached |
| 09 | The write opens with harmless text and hides the payload after character 80 | The approver is shown the whole content |

The test also checks that every recorded turn is used, so a stale recording fails loudly, and that no case lets a note
change unless the fixture says so. Adding a case is adding a JSON file.

The fixtures are synthetic and marked `"source": "synthetic"`. They prove what the guard code does when a model
misbehaves. They do not show how likely a real model is to misbehave, or that a real model produces these turns. Unit
tests never call a model, and CI never records.

### Re-recording from a real model (manual)

```
./mvnw -q -pl 04-agent-guardrails exec:java -Dexec.args='ask "What is in my todo note?" --record /tmp/run.json'
```

This writes `source`, `question`, the seeded notes and the turns the model gave. To turn it into a case, copy the file
into `src/test/resources/injection/`, plant the attack in `notes`, add `approver`, `budget` if needed and `expect`
(`outcome`, `approvalRequests`, and optionally `toolStatuses`, `toolDataContains`, `approvalSummariesContain`,
`approvalDetailsContain`, `approvalSummariesExclude`), and change `source` to `recorded`. Check Ollama reports token usage on this run: if it
does not, every run stops with `USAGE_UNREPORTED` by design.
