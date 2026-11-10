package io.github.hvmello.aienglab.agentguardrails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hvmello.aienglab.toolcalling.InvalidArgumentsException;
import io.github.hvmello.aienglab.toolcalling.ToolFailure;
import org.junit.jupiter.api.Test;

class NoteToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    @Test
    void nameValidation() {
        assertThat(NoteStore.validName("todo-2")).isTrue();
        assertThat(NoteStore.validName("a".repeat(40))).isTrue();
        assertThat(NoteStore.validName("a".repeat(41))).isFalse();
        assertThat(NoteStore.validName("")).isFalse();
        assertThat(NoteStore.validName(null)).isFalse();
        assertThat(NoteStore.validName("../etc/passwd")).isFalse();
        assertThat(NoteStore.validName("Todo")).isFalse();
        assertThat(NoteStore.validName("a b")).isFalse();
        assertThat(NoteStore.validName("a\n")).isFalse();
    }

    @Test
    void writeCreatesThenOverwrites() {
        var store = new NoteStore();

        assertThat(store.write("a", "one")).isTrue();
        assertThat(store.write("a", "two")).isFalse();
        assertThat(store.read("a")).contains("two");
        assertThat(store.read("missing")).isEmpty();
        assertThat(store.names()).containsExactly("a");
    }

    @Test
    void sizeCaps() {
        var store = new NoteStore();

        assertThatThrownBy(() -> store.write("a", "x".repeat(NoteStore.MAX_CONTENT_CHARS + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.write("Bad Name", "x")).isInstanceOf(IllegalArgumentException.class);
        for (int i = 0; i < NoteStore.MAX_NOTES; i++) {
            store.write("n" + i, "x");
        }
        assertThatThrownBy(() -> store.write("one-more", "x")).isInstanceOf(IllegalArgumentException.class);
        store.write("n0", "overwriting an existing note is still fine");
        assertThat(store.names()).hasSize(NoteStore.MAX_NOTES);
    }

    @Test
    void listReturnsNames() throws Exception {
        var store = new NoteStore();
        store.write("b", "1");
        store.write("a", "2");
        var tool = new ListNotesTool(store);

        JsonNode data = tool.execute(tool.parse(json("{}")));

        assertThat(data.toString()).isEqualTo("{\"notes\":[\"a\",\"b\"]}");
    }

    @Test
    void listRejectsAnyArgument() {
        var tool = new ListNotesTool(new NoteStore());

        assertThatThrownBy(() -> tool.parse(json("{\"approved\":true}"))).isInstanceOf(InvalidArgumentsException.class);
        assertThatThrownBy(() -> tool.parse(json("[]"))).isInstanceOf(InvalidArgumentsException.class);
    }

    @Test
    void readReturnsTheContentOrAFailure() throws Exception {
        var store = new NoteStore();
        store.write("a", "hello");
        var tool = new ReadNoteTool(store);

        assertThat(tool.execute(tool.parse(json("{\"name\":\"a\"}"))).toString())
                .isEqualTo("{\"name\":\"a\",\"content\":\"hello\"}");
        assertThatThrownBy(() -> tool.execute(tool.parse(json("{\"name\":\"zzz\"}"))))
                .isInstanceOfSatisfying(ToolFailure.class, f -> assertThat(f.retryable()).isFalse());
    }

    @Test
    void readValidatesTheName() {
        var tool = new ReadNoteTool(new NoteStore());

        assertThatThrownBy(() -> tool.parse(json("{\"name\":\"../x\"}"))).isInstanceOf(InvalidArgumentsException.class);
        assertThatThrownBy(() -> tool.parse(json("{}"))).isInstanceOf(InvalidArgumentsException.class);
        assertThatThrownBy(() -> tool.parse(json("{\"name\":5}"))).isInstanceOf(InvalidArgumentsException.class);
    }

    @Test
    void writeValidatesStrictly() {
        var tool = new WriteNoteTool(new NoteStore());

        assertThatThrownBy(() -> tool.parse(json("{\"name\":\"a\"}"))).isInstanceOf(InvalidArgumentsException.class);
        assertThatThrownBy(() -> tool.parse(json("{\"name\":\"a\",\"content\":\"x\",\"approved\":true}")))
                .isInstanceOf(InvalidArgumentsException.class);
        assertThatThrownBy(() -> tool.parse(json("{\"name\":\"A\",\"content\":\"x\"}")))
                .isInstanceOf(InvalidArgumentsException.class);
        assertThatThrownBy(() -> tool.parse(json("{\"name\":\"a\",\"content\":\"" + "x".repeat(3000) + "\"}")))
                .isInstanceOf(InvalidArgumentsException.class);
    }

    @Test
    void writeExecutesAndReportsCreatedOrOverwritten() throws Exception {
        var store = new NoteStore();
        var tool = new WriteNoteTool(store);

        assertThat(tool.execute(tool.parse(json("{\"name\":\"a\",\"content\":\"1\"}"))).toString())
                .isEqualTo("{\"name\":\"a\",\"written\":true,\"created\":true}");
        assertThat(tool.execute(tool.parse(json("{\"name\":\"a\",\"content\":\"2\"}"))).path("created").asBoolean())
                .isFalse();
        assertThat(store.read("a")).contains("2");
    }

    @Test
    void theApprovalSummaryNamesTheActionAndCutsLongContent() throws Exception {
        var store = new NoteStore();
        store.write("a", "old");
        var tool = new WriteNoteTool(store);

        String overwrite = tool.summarize(tool.parse(json("{\"name\":\"a\",\"content\":\"new\"}")));
        String create = tool.summarize(tool.parse(json("{\"name\":\"b\",\"content\":\"" + "y".repeat(500) + "\"}")));

        assertThat(overwrite).isEqualTo("overwrite note 'a' with 3 characters: new");
        assertThat(create).startsWith("create note 'b' with 500 characters: yyy").endsWith("...");
        assertThat(create.length()).isLessThan(150);
    }

    @Test
    void toolsRegisterWithTheRightPermissions() {
        var store = new NoteStore();

        assertThat(new ListNotesTool(store).asAgentTool().permission()).isEqualTo(Permission.READ);
        assertThat(new ReadNoteTool(store).asAgentTool().permission()).isEqualTo(Permission.READ);
        assertThat(new WriteNoteTool(store).asAgentTool().permission()).isEqualTo(Permission.WRITE);
    }
}
