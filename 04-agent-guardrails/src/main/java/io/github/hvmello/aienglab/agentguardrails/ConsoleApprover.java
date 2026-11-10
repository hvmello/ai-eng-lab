package io.github.hvmello.aienglab.agentguardrails;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.PrintWriter;

/** Asks on the terminal. Only "y" approves; anything else, end of input or a read error denies. */
public final class ConsoleApprover implements Approver {

    private final BufferedReader in;
    private final PrintWriter out;

    public ConsoleApprover(BufferedReader in, PrintWriter out) {
        this.in = in;
        this.out = out;
    }

    /** A prompt on the system console, or {@link Approver#denyAll()} when there is no console. */
    public static Approver fromSystem() {
        Console console = System.console();
        return console == null ? Approver.denyAll() : new ConsoleApprover(new BufferedReader(console.reader()), console.writer());
    }

    @Override
    public Decision decide(ApprovalRequest request) {
        out.println("Approval needed [step " + request.step() + "] " + request.tool() + ": " + request.summary());
        out.print("Allow? [y/N] ");
        out.flush();
        try {
            String line = in.readLine();
            return line != null && line.strip().equalsIgnoreCase("y") ? Decision.APPROVED : Decision.DENIED;
        } catch (IOException e) {
            return Decision.DENIED;
        }
    }
}
