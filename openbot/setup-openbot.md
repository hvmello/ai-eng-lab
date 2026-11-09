# OpenBot setup

Written against OpenBot 0.66. It is a development preview, so labels may change.

## Before you start

- Ollama is running with `gpt-oss:20b`, and models are stored outside the system disk through `OLLAMA_MODELS`.
- `OLLAMA_CONTEXT_LENGTH=32768` is set. Ollama's default of 4096 is too small for coding agents and silently
  drops context. Check with `ollama ps`; the model should show 100% GPU.
- Claude Code is signed in with `claude auth login`.
- `gh` is signed in. Prefer a fine-grained token limited to the portfolio repositories, because a general login
  also reaches every private repository on the account.

## Agents

Create four agents with `F:\Borderless\agents-workspace` as the workspace. Paste the file shown as the instructions,
and turn on **Local scripts** for each, since the pipeline needs it. The names must match exactly.

| Agent | Provider and model | Instructions |
|---|---|---|
| Planner | Claude, Opus 5.5 | `planner.md` |
| Dev | Claude, Sonnet 5.5 | `dev.md` |
| Reviewer | Claude, Opus 5.5 | `reviewer.md` |
| Merger | Ollama, `gpt-oss:20b` | `merger.md` |

OpenBot detects a running Ollama server. If it does not, add a custom OpenAI-compatible endpoint at
`http://localhost:11434/v1`.

## Permissions

With **Ask first**, an unattended run stops at the first approval and labels the issue `needs-heitor`. To run
unattended, use broader access only after branch protection and a restricted token are in place.

## Try it

```powershell
powershell -File openbot\pipeline.ps1 -DryRun
```

## Limits to keep in mind

- OpenBot is licensed PolyForm Noncommercial.
- Claude agents use your subscription. Anthropic's terms describe it as being for ordinary use of Claude Code,
  and OpenBot does not check compliance, so review those terms before running on a schedule. An API key is the alternative.
