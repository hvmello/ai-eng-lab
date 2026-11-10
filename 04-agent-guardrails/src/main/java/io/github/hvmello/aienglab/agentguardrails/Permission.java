package io.github.hvmello.aienglab.agentguardrails;

/** What a tool may do. Every tool declares one; there is no default, so a forgotten permission cannot register. */
public enum Permission {
    /** Reads state only. Runs without asking. */
    READ,
    /** Changes state. Runs only after an explicit approval. */
    WRITE
}
