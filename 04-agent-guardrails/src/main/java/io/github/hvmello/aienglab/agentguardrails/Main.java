package io.github.hvmello.aienglab.agentguardrails;

import io.github.hvmello.aienglab.shared.LlmCallException;
import io.github.hvmello.aienglab.shared.LlmClient;
import io.github.hvmello.aienglab.shared.LlmClients;
import io.github.hvmello.aienglab.shared.Message;
import io.github.hvmello.aienglab.toolcalling.ToolResult;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Command line: {@code ask "<question>" [--approve prompt|deny] [--record <file>]}. Configuration comes from the
 * environment: {@code OLLAMA_BASE_URL} and {@code OLLAMA_MODEL}. It seeds a few demo notes, prints one line per tool
 * call and then the answer and the spend. Arguments and note bodies are never printed. Writes are denied unless
 * {@code --approve prompt} is given and the person at the terminal answers "y".
 */
public final class Main {

    static final String DEFAULT_BASE_URL = "http://localhost:11434";
    static final String DEFAULT_MODEL = "gpt-oss:20b";

    private Main() {}

    public static void main(String[] args) {
        String baseUrl = envOrDefault("OLLAMA_BASE_URL", DEFAULT_BASE_URL);
        String model = envOrDefault("OLLAMA_MODEL", DEFAULT_MODEL);
        System.exit(run(
                args, () -> LlmClients.ollama(baseUrl, model), ConsoleApprover.fromSystem(), Budget.defaults(),
                System.out, System.err));
    }

    static int run(
            String[] args,
            Supplier<LlmClient> clientFactory,
            Approver promptApprover,
            Budget budget,
            PrintStream out,
            PrintStream err) {
        String question;
        boolean prompt = false;
        Path recordFile = null;
        try {
            if (args.length == 0 || !args[0].equals("ask")) {
                throw new IllegalArgumentException(args.length == 0 ? "Missing command" : "Unknown command: " + args[0]);
            }
            List<String> words = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--approve" -> prompt = parseApprove(next(args, ++i, "--approve"));
                    case "--record" -> recordFile = Path.of(next(args, ++i, "--record"));
                    default -> words.add(args[i]);
                }
            }
            question = String.join(" ", words);
            if (question.isBlank()) {
                throw new IllegalArgumentException("The ask command needs a question");
            }
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(usage());
            return 2;
        }
        NoteStore store = new NoteStore();
        seed(store);
        java.util.Map<String, String> seeded = store.snapshot();
        RecordingLlmClient recorder = recordFile == null ? null : new RecordingLlmClient(clientFactory.get());
        LlmClient client = recorder != null ? recorder : clientFactory.get();
        List<AgentTool<?>> tools = List.of(
                new ListNotesTool(store).asAgentTool(),
                new ReadNoteTool(store).asAgentTool(),
                new WriteNoteTool(store).asAgentTool());
        GuardedAgent agent = new GuardedAgent(client, tools, budget, prompt ? promptApprover : Approver.denyAll());
        int code;
        try {
            code = report(agent.run(question), budget, out, err);
        } catch (LlmCallException e) {
            err.println("Model call failed: " + e.getMessage());
            code = 1;
        }
        if (recorder != null) {
            try {
                recorder.write(recordFile, question, seeded);
                out.println("Recorded " + recorder.turns().size() + " turns to " + recordFile);
            } catch (IOException e) {
                err.println("Could not write the recording: " + e.getMessage());
                code = 1;
            }
        }
        return code;
    }

    private static int report(AgentOutcome outcome, Budget budget, PrintStream out, PrintStream err) {
        printTrace(outcome.transcript(), out);
        int code;
        switch (outcome) {
            case AgentOutcome.Answered answered -> {
                out.println(answered.text());
                code = 0;
            }
            case AgentOutcome.StepLimitReached ignored -> {
                err.println("Stopped: the model was still asking for tools after " + budget.maxSteps() + " steps.");
                code = 1;
            }
            case AgentOutcome.BudgetExhausted exhausted -> {
                err.println("Stopped: budget exhausted (" + exhausted.reason() + ").");
                code = 1;
            }
            case AgentOutcome.Truncated ignored -> {
                err.println("Stopped: the model output was cut off, so there is no complete answer.");
                code = 1;
            }
        }
        out.println("Spend: " + outcome.spend());
        return code;
    }

    /** One line per tool call: the step, the tool and its status. Arguments and data are left out on purpose. */
    static void printTrace(List<Message> transcript, PrintStream out) {
        int step = 0;
        for (Message message : transcript) {
            if (message.role() == Message.Role.ASSISTANT && !message.toolCalls().isEmpty()) {
                step++;
            } else if (message.role() == Message.Role.TOOL) {
                String status = ToolResult.statusOf(message.content());
                if (status.equals("error " + ToolResult.ErrorType.APPROVAL_DENIED.wire())) {
                    status = "denied";
                }
                out.println("[step " + step + "] " + message.toolName() + " -> " + status);
            }
        }
    }

    static void seed(NoteStore store) {
        store.write("welcome", "This is a demo workspace. Notes are plain text.");
        store.write("shopping", "milk, eggs, coffee");
        store.write("todo", "call the dentist; renew the passport");
    }

    private static String next(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[index];
    }

    private static boolean parseApprove(String value) {
        return switch (value) {
            case "prompt" -> true;
            case "deny" -> false;
            default -> throw new IllegalArgumentException("Unknown --approve value: " + value);
        };
    }

    private static String usage() {
        return "Usage: ask \"<question>\" [--approve prompt|deny] [--record <file>]\n"
                + "Writes are denied unless --approve prompt is given. Environment: OLLAMA_BASE_URL (default "
                + DEFAULT_BASE_URL + "), OLLAMA_MODEL (default " + DEFAULT_MODEL + ")";
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
