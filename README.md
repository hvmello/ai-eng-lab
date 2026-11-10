# AI Engineering Lab

Hands-on AI engineering modules in Java, built by a senior backend engineer moving into the field. Every
module runs on local models through Ollama at no cost, with hosted providers optional.

> **Status: module 01 is implemented.** The other modules are still planned.

| Module | Topic | Status |
|---|---|---|
| [01-llm-basics](01-llm-basics/README.md) | Chat, streaming, parameters, structured output into Java records | Implemented |
| 02-tool-calling | Java tools, invalid calls and error handling | Planned |
| [03-mcp-server](03-mcp-server/README.md) | A read-only MCP server in Java over Streamable HTTP, behind OAuth | Implemented, not yet validated with a real client |
| 04-agent-guardrails | Tool loop, step limits, permissions, prompt injection tests | Planned |
| 08-capstone | [evidence-gate](https://github.com/hvmello/evidence-gate): RAG, evaluation, guardrails and observability | In progress |

Build and test with `./mvnw verify` (JDK 21 or newer). `shared/` holds the provider abstraction every module uses.

See the [roadmap](ROADMAP.md) for goals and acceptance criteria.

## How this repository is built

Agents work through issues and pull requests under Git Flow: Claude Opus plans and reviews, Claude Sonnet implements,
and a local model on Ollama performs the merge. Whether a change ships is decided in code, from a verdict tied to
the reviewed commit and green CI. Rules are in [AGENTS.md](AGENTS.md), and setup is in [openbot/](openbot/setup-openbot.md). The author reviews the work and answers the questions in each module's
`LEARNING.md` before it is merged.
