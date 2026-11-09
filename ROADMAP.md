# Roadmap

RAG, evaluation, observability and cost live in [evidence-gate](https://github.com/hvmello/evidence-gate), so they
are not built twice. Module 01 comes first, since it also answers whether LangChain4j delivers structured output.

## 01-llm-basics

**Learn:** the request and response model of an LLM, streaming, parameters, structured output.
**Build:** chat, streaming and a typed extraction into a validated Java record, on Ollama.
**Accepted when:** it runs with no API key; invalid model output fails with a typed error; tests use a stub;
the differences between Ollama and hosted Claude are documented, including that current Claude models reject
`temperature`.

**Questions for Heitor:**
1. What happens to your extraction when the model returns valid JSON with a wrong value?
2. When is streaming worth its extra complexity on a backend?
3. Where would you put retries, and what must they never retry?

## 02-tool-calling

**Learn:** how a model requests tools and how the application stays in control.
**Build:** a model calling Java tools against a public API.
**Accepted when:** invalid calls and tool errors go back to the model as structured results; loops are bounded;
Spring AI and LangChain4j are compared in an ADR.

**Questions for Heitor:**
1. Which side validates tool arguments, and why?
2. What should a tool error tell the model, and what should it hide?
3. How would you stop a retry storm caused by a tool that keeps failing?

## 03-mcp-server

**Learn:** the Model Context Protocol and its trust boundaries.
**Build:** an MCP server in Java exposing tools and resources.
**Accepted when:** it works when connected to Claude Code; tools are read-only unless stated; the protocol is
covered by tests.

**Questions for Heitor:**
1. What can a malicious tool description do to a client?
2. Which transport would you deploy for a shared internal server?
3. How does authorization differ from a normal REST API?

## 04-agent-guardrails

**Learn:** when a loop is justified and how to constrain it.
**Build:** an agent with a tool loop, step limits and permissions.
**Accepted when:** step and cost limits stop the loop; a write tool requires approval; prompt injection tests
run against a recorded set.

**Questions for Heitor:**
1. Which of your tasks really need an agent instead of a fixed workflow?
2. What is the worst action this agent can take, and what prevents it?
3. How do you test behaviour that is not deterministic?

## 08-capstone

[evidence-gate](https://github.com/hvmello/evidence-gate), with its own roadmap.
