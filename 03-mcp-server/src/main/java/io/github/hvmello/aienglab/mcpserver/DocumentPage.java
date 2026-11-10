package io.github.hvmello.aienglab.mcpserver;

import java.time.Instant;
import java.util.List;

/** Result shapes of the document queries. */
public final class DocumentPage {

  private DocumentPage() {}

  public record Summary(long id, String title, Instant updatedAt, int length) {}

  public record Slice(
      long id, String title, Instant updatedAt, int totalChars, int offset, String text) {
    /** Characters in the text as PostgreSQL counts them (code points), not UTF-16 units. */
    public int chars() {
      return text.codePointCount(0, text.length());
    }

    public boolean hasMore() {
      return (long) offset + chars() < totalChars;
    }
  }

  /** A page of items plus the position to continue from, or -1 when there is no more. */
  public record Page<T>(List<T> items, long nextPosition) {
    public boolean hasMore() {
      return nextPosition >= 0;
    }
  }
}
