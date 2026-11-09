# 1. LLM library

**Status:** Accepted

## Decision

LangChain4j is the default. Module 02 also implements its tools with Spring AI, to compare the two.

## Why

LangChain4j does not tie the code to a Spring release, and it has first-class Ollama and Anthropic modules.
Spring AI is the natural alternative for Spring teams, so a direct comparison is more useful than choosing
by reputation.

## Consequences

Application code depends on a small provider abstraction in `shared/`, not on either library. Whether LangChain4j
delivers structured output from Claude is unverified and is answered in module 01.
