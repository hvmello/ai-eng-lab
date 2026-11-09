# AI Engineering Lab

Hands-on AI engineering modules in Java, built by a senior backend engineer moving into the field. Every
module runs on local models through Ollama at no cost, with hosted providers optional.

> **Status: repository structure only.** No module is implemented yet.

| Module | Topic | Status |
|---|---|---|
| 01-llm-basics | Chat, streaming, parameters, structured output into Java records | Planned |
| 02-tool-calling | Java tools, invalid calls and error handling | Planned |
| 03-mcp-server | An MCP server in Java, exposing tools and resources | Planned |
| 04-agent-guardrails | Tool loop, step limits, permissions, prompt injection tests | Planned |
| 08-capstone | [evidence-gate](https://github.com/hvmello/evidence-gate): RAG, evaluation, guardrails and observability | In progress |

See the [roadmap](ROADMAP.md) for goals and acceptance criteria.

## How this repository is built

Three agent roles (Tutor, Dev and Reviewer) work through issues and pull requests under Git Flow. Local or
hosted models are chosen by each issue's complexity. Rules are in [AGENTS.md](AGENTS.md), and setup is in
[openbot/](openbot/setup-openbot.md). The author reviews the work and answers the questions in each module's
`LEARNING.md` before it is merged.
