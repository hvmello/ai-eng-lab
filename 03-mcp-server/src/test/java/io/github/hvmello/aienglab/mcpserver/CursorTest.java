package io.github.hvmello.aienglab.mcpserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hvmello.aienglab.mcpserver.Cursor.InvalidCursorException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class CursorTest {

  private static final byte[] KEY = "k".repeat(40).getBytes(StandardCharsets.UTF_8);
  private static final byte[] OTHER_KEY = "o".repeat(40).getBytes(StandardCharsets.UTF_8);

  @Test
  void roundTrips() {
    String token = new Cursor(Cursor.SEARCH, "abc", 42).encode(KEY);

    Cursor decoded = Cursor.decode(token, KEY, Cursor.SEARCH, "abc");

    assertThat(decoded).isEqualTo(new Cursor(Cursor.SEARCH, "abc", 42));
  }

  @Test
  void isOpaqueUrlSafeText() {
    String token = new Cursor(Cursor.LIST, "", 7).encode(KEY);

    assertThat(token).matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
    assertThat(token).doesNotContain("list");
  }

  @Test
  void rejectsEditedPayload() {
    String token = new Cursor(Cursor.LIST, "", 7).encode(KEY);
    String mac = token.substring(token.indexOf(".") + 1);
    String forgedPayload =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("list||9999".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> Cursor.decode(forgedPayload + "." + mac, KEY, Cursor.LIST, ""))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void rejectsEditedSignature() {
    String token = new Cursor(Cursor.LIST, "", 7).encode(KEY);
    String edited = token.substring(0, token.length() - 2) + (token.endsWith("AA") ? "BB" : "AA");

    assertThatThrownBy(() -> Cursor.decode(edited, KEY, Cursor.LIST, ""))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void rejectsCursorSignedWithAnotherKey() {
    String foreign = new Cursor(Cursor.LIST, "", 7).encode(OTHER_KEY);

    assertThatThrownBy(() -> Cursor.decode(foreign, KEY, Cursor.LIST, ""))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void rejectsCursorIssuedForAnotherToolOrBinding() {
    String search = new Cursor(Cursor.SEARCH, "q1", 5).encode(KEY);

    assertThatThrownBy(() -> Cursor.decode(search, KEY, Cursor.LIST, "q1"))
        .isInstanceOf(InvalidCursorException.class);
    assertThatThrownBy(() -> Cursor.decode(search, KEY, Cursor.SEARCH, "q2"))
        .isInstanceOf(InvalidCursorException.class);
  }

  @Test
  void rejectsGarbage() {
    for (String garbage : new String[] {"", ".", "a.", ".b", "not a cursor", "%%%.%%%", "a.b.c"}) {
      assertThatThrownBy(() -> Cursor.decode(garbage, KEY, Cursor.LIST, ""))
          .as(garbage)
          .isInstanceOf(InvalidCursorException.class);
    }
    assertThatThrownBy(() -> Cursor.decode("x".repeat(5000), KEY, Cursor.LIST, ""))
        .isInstanceOf(InvalidCursorException.class);
  }
}
