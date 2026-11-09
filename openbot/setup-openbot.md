# OpenBot setup

Written against OpenBot 0.66. It is a development preview, so labels may change.

## Before you start

- Ollama is running with `gpt-oss:20b`, and models are stored outside the system disk through `OLLAMA_MODELS`.
- Claude Code is signed in with `claude auth login`.
- `gh` is signed in. Prefer a fine-grained token limited to this repository, because a general login also reaches
  every private repository on the account.

## Agents

Create five agents with this repository as the workspace and permission **Ask first**. Paste the file shown as
the instructions. Enable **Local scripts** on each, since `route.ps1` needs it.

| Agent | Provider and model | Instructions |
|---|---|---|
| Tutor | Claude, `claude-sonnet-5-5` | `tutor.md` |
| Dev-Local | Ollama, `gpt-oss:20b` | `dev.md` |
| Dev-Claude | Claude, set by `route.ps1` | `dev.md` |
| Reviewer-Local | Ollama, `gpt-oss:20b` | `reviewer.md` |
| Reviewer-Claude | Claude, set by `route.ps1` | `reviewer.md` |

OpenBot detects a running Ollama server. If it does not, add a custom OpenAI-compatible endpoint at
`http://localhost:11434/v1`.

## Channel

Create a channel named **AI Eng Lab** with the five agents and the Tutor as task owner.

## Try the routing

```powershell
powershell -File openbot\route.ps1 -Repo hvmello/ai-eng-lab -Issue 1 -Stage dev -DryRun
```

## Limits to keep in mind

- OpenBot is licensed PolyForm Noncommercial.
- Claude agents use your subscription. Anthropic's terms describe it as being for ordinary use of Claude Code,
  and OpenBot does not check compliance, so review those terms before enabling routines. An API key is the alternative.
