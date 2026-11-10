package io.github.hvmello.aienglab.llmbasics;

import io.github.hvmello.aienglab.shared.ChatRequest;
import io.github.hvmello.aienglab.shared.ChatResult;
import io.github.hvmello.aienglab.shared.LlmCallException;
import io.github.hvmello.aienglab.shared.LlmClient;
import io.github.hvmello.aienglab.shared.LlmClients;
import io.github.hvmello.aienglab.shared.Message;
import io.github.hvmello.aienglab.shared.StreamListener;
import java.io.PrintStream;
import java.util.Arrays;

/**
 * Command line for the module: {@code chat}, {@code stream} and {@code extract}. Configuration comes from the
 * environment only: {@code OLLAMA_BASE_URL} and {@code OLLAMA_MODEL}. No API key is needed.
 */
public final class Main {

    static final String DEFAULT_BASE_URL = "http://localhost:11434";
    static final String DEFAULT_MODEL = "gpt-oss:20b";

    static final String SAMPLE_INVOICE = """
            Invoice from Northwind Traders, issued 2026-03-14.
            2 x Ergonomic keyboard at 49.90 USD each
            1 x USB-C dock at 129.00 USD
            Total due: 228.80 USD
            """;

    private Main() {}

    public static void main(String[] args) {
        String baseUrl = envOrDefault("OLLAMA_BASE_URL", DEFAULT_BASE_URL);
        String model = envOrDefault("OLLAMA_MODEL", DEFAULT_MODEL);
        System.exit(run(args, LlmClients.ollama(baseUrl, model), System.out, System.err));
    }

    static int run(String[] args, LlmClient client, PrintStream out, PrintStream err) {
        if (args.length == 0) {
            err.println(usage());
            return 2;
        }
        String command = args[0];
        String input = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        try {
            switch (command) {
                case "chat" -> {
                    ChatResult result = client.chat(ChatRequest.of(Message.user(requireInput(input, "chat"))));
                    out.println(result.text());
                    out.println("[finish=" + result.finishReason() + ", usage=" + result.usage() + "]");
                }
                case "stream" -> {
                    return stream(client, requireInput(input, "stream"), out, err);
                }
                case "extract" -> {
                    String text = input.isBlank() ? SAMPLE_INVOICE : input;
                    out.println(new InvoiceExtractor(client).extract(text));
                }
                default -> {
                    err.println("Unknown command: " + command);
                    err.println(usage());
                    return 2;
                }
            }
            return 0;
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            err.println(usage());
            return 2;
        } catch (ExtractionException e) {
            err.println(e.getClass().getSimpleName() + ": " + e.getMessage());
            err.println("Raw output: " + e.rawOutput());
            return 1;
        } catch (LlmCallException e) {
            err.println("Model call failed: " + e.getMessage());
            return 1;
        }
    }

    private static int stream(LlmClient client, String prompt, PrintStream out, PrintStream err) {
        int[] exit = {0};
        client.stream(ChatRequest.of(Message.user(prompt)), new StreamListener() {
            @Override
            public void onToken(String token) {
                out.print(token);
                out.flush();
            }

            @Override
            public void onComplete(ChatResult result) {
                out.println();
                out.println("[finish=" + result.finishReason() + ", usage=" + result.usage() + "]");
            }

            @Override
            public void onError(LlmCallException error) {
                err.println();
                err.println("Stream failed: " + error.getMessage());
                exit[0] = 1;
            }
        });
        return exit[0];
    }

    private static String requireInput(String input, String command) {
        if (input.isBlank()) {
            throw new IllegalArgumentException("The " + command + " command needs a prompt");
        }
        return input;
    }

    private static String usage() {
        return "Usage: chat <prompt> | stream <prompt> | extract [invoice text]\n"
                + "Environment: OLLAMA_BASE_URL (default " + DEFAULT_BASE_URL + "), OLLAMA_MODEL (default "
                + DEFAULT_MODEL + ")";
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
