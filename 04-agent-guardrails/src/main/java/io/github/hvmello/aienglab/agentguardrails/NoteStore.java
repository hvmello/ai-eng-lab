package io.github.hvmello.aienglab.agentguardrails;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The toy workspace: named notes in memory. Names are {@code [a-z0-9-]{1,40}}, so there is no path to traverse, and
 * the sizes are capped. The worst thing an agent can do here is overwrite a note, which is what the approval gate
 * stands in front of.
 */
public final class NoteStore {

    public static final int MAX_NOTES = 50;
    public static final int MAX_CONTENT_CHARS = 2_000;

    private static final Pattern NAME = Pattern.compile("[a-z0-9-]{1,40}");

    private final Map<String, String> notes = new TreeMap<>();

    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    public synchronized List<String> names() {
        return List.copyOf(notes.keySet());
    }

    public synchronized Optional<String> read(String name) {
        return Optional.ofNullable(notes.get(name));
    }

    public synchronized boolean exists(String name) {
        return notes.containsKey(name);
    }

    /**
     * Creates or overwrites a note.
     *
     * @return true when the note is new, false when it was overwritten
     * @throws IllegalArgumentException on a bad name, too much content or too many notes
     */
    public synchronized boolean write(String name, String content) {
        if (!validName(name)) {
            throw new IllegalArgumentException("note name must match [a-z0-9-]{1,40}");
        }
        if (content == null || content.length() > MAX_CONTENT_CHARS) {
            throw new IllegalArgumentException("note content must be at most " + MAX_CONTENT_CHARS + " characters");
        }
        boolean created = !notes.containsKey(name);
        if (created && notes.size() >= MAX_NOTES) {
            throw new IllegalArgumentException("the workspace holds at most " + MAX_NOTES + " notes");
        }
        notes.put(name, content);
        return created;
    }

    /** A copy of every note, for tests and for the recorder. */
    public synchronized Map<String, String> snapshot() {
        return new TreeMap<>(notes);
    }
}
