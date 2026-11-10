package io.github.hvmello.aienglab.toolcalling;

import io.github.hvmello.aienglab.shared.LlmCallException;
import io.github.hvmello.aienglab.shared.LlmClient;
import io.github.hvmello.aienglab.shared.LlmClients;
import io.github.hvmello.aienglab.shared.Message;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Command line: {@code ask "<question>" [--lib langchain4j|springai]}. Configuration comes from the environment:
 * {@code OLLAMA_BASE_URL} and {@code OLLAMA_MODEL}. No API key is needed. It prints the answer and a short trace of
 * each tool call (tool and ok or error type), never raw upstream bodies.
 */
public final class Main {

    static final String DEFAULT_BASE_URL = "http://localhost:11434";
    static final String DEFAULT_MODEL = "gpt-oss:20b";
    private static final Duration MODEL_TIMEOUT = Duration.ofMinutes(5);

    /** The library behind the {@link LlmClient}. */
    enum Lib { LANGCHAIN4J, SPRINGAI }

    private Main() {}

    public static void main(String[] args) {
        String baseUrl = envOrDefault("OLLAMA_BASE_URL", DEFAULT_BASE_URL);
        String model = envOrDefault("OLLAMA_MODEL", DEFAULT_MODEL);
        OpenMeteoClient weather = OpenMeteoClient.publicApi();
        List<Tool<?>> tools = List.of(new GeocodeCityTool(weather), new GetForecastTool(weather));
        Function<Lib, LlmClient> clients = lib -> switch (lib) {
            case LANGCHAIN4J -> LlmClients.ollama(baseUrl, model);
            case SPRINGAI -> new SpringAiLlmClient(baseUrl, model, MODEL_TIMEOUT);
        };
        System.exit(run(args, clients, tools, LoopLimits.defaults(), System.out, System.err));
    }

    static int run(
            String[] args,
            Function<Lib, LlmClient> clients,
            List<Tool<?>> tools,
            LoopLimits limits,
            PrintStream out,
            PrintStream err) {
        try {
            Lib lib = Lib.LANGCHAIN4J;
            List<String> words = new ArrayList<>();
            if (args.length == 0 || !args[0].equals("ask")) {
                throw new IllegalArgumentException(args.length == 0 ? "Missing command" : "Unknown command: " + args[0]);
            }
            for (int i = 1; i < args.length; i++) {
                if (args[i].equals("--lib")) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--lib needs a value");
                    }
                    lib = parseLib(args[++i]);
                } else {
                    words.add(args[i]);
                }
            }
            String question = String.join(" ", words);
            if (question.isBlank()) {
                throw new IllegalArgumentException("The ask command needs a question");
            }
            return ask(clients.apply(lib), tools, limits, question, out, err);
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(usage());
            return 2;
        }
    }

    private static int ask(
            LlmClient client, List<Tool<?>> tools, LoopLimits limits, String question, PrintStream out, PrintStream err) {
        try {
            LoopOutcome outcome = new ToolLoop(client, tools, limits).run(question);
            printTrace(outcome.transcript(), out);
            switch (outcome) {
                case LoopOutcome.Answered answered -> {
                    out.println(answered.text());
                    return 0;
                }
                case LoopOutcome.StepLimitReached ignored -> {
                    err.println("Stopped: the model was still asking for tools after " + limits.maxSteps() + " steps.");
                    return 1;
                }
                case LoopOutcome.Truncated ignored -> {
                    err.println("Stopped: the model output was cut off, so there is no complete answer.");
                    return 1;
                }
            }
        } catch (LlmCallException e) {
            err.println("Model call failed: " + e.getMessage());
            return 1;
        }
    }

    /** One line per tool call: the step, the tool and its status. Arguments and data are left out on purpose. */
    static void printTrace(List<Message> transcript, PrintStream out) {
        int step = 0;
        for (int i = 0; i < transcript.size(); i++) {
            Message message = transcript.get(i);
            if (message.role() == Message.Role.ASSISTANT && !message.toolCalls().isEmpty()) {
                step++;
            } else if (message.role() == Message.Role.TOOL) {
                out.println("[step " + step + "] " + message.toolName() + " -> " + ToolResult.statusOf(message.content()));
            }
        }
    }

    private static Lib parseLib(String value) {
        return switch (value) {
            case "langchain4j" -> Lib.LANGCHAIN4J;
            case "springai" -> Lib.SPRINGAI;
            default -> throw new IllegalArgumentException("Unknown --lib value: " + value);
        };
    }

    private static String usage() {
        return "Usage: ask \"<question>\" [--lib langchain4j|springai]\n"
                + "Environment: OLLAMA_BASE_URL (default " + DEFAULT_BASE_URL + "), OLLAMA_MODEL (default "
                + DEFAULT_MODEL + ")";
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
