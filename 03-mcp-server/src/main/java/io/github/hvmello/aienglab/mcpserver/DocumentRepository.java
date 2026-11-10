package io.github.hvmello.aienglab.mcpserver;

import io.github.hvmello.aienglab.mcpserver.DocumentPage.Page;
import io.github.hvmello.aienglab.mcpserver.DocumentPage.Slice;
import io.github.hvmello.aienglab.mcpserver.DocumentPage.Summary;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Read-only access to documents. Every value reaches SQL as a bound parameter. */
public class DocumentRepository {

  private static final String FTS_DOC = "to_tsvector('english', title || ' ' || body)";

  private static final RowMapper<Summary> SUMMARY =
      (rs, n) ->
          new Summary(
              rs.getLong("id"),
              rs.getString("title"),
              rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
              rs.getInt("len"));

  private final JdbcTemplate jdbc;

  public DocumentRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Keyset pagination: documents with an id greater than {@code afterId}. */
  public Page<Summary> list(long afterId, int limit) {
    List<Summary> rows =
        jdbc.query(
            "SELECT id, title, updated_at, char_length(body) AS len FROM documents"
                + " WHERE id > ? ORDER BY id LIMIT ?",
            SUMMARY,
            afterId,
            limit + 1);
    return page(rows, limit, afterId, true);
  }

  /** Full-text search, ranked, with offset pagination (a rank has no stable key). */
  public Page<Summary> search(String query, long offset, int limit) {
    List<Summary> rows =
        jdbc.query(
            "SELECT id, title, updated_at, char_length(body) AS len FROM documents WHERE "
                + FTS_DOC
                + " @@ plainto_tsquery('english', ?) ORDER BY ts_rank("
                + FTS_DOC
                + ", plainto_tsquery('english', ?)) DESC, id LIMIT ? OFFSET ?",
            SUMMARY,
            query,
            query,
            limit + 1,
            offset);
    return page(rows, limit, offset, false);
  }

  /** A slice of the body: {@code maxChars} characters starting at {@code offset}. */
  public Optional<Slice> read(long id, long offset, int maxChars) {
    List<Slice> rows =
        jdbc.query(
            "SELECT id, title, updated_at, char_length(body) AS len,"
                + " substring(body from CAST(? AS integer) for CAST(? AS integer)) AS part FROM documents WHERE id = ?",
            (rs, n) ->
                new Slice(
                    rs.getLong("id"),
                    rs.getString("title"),
                    rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                    rs.getInt("len"),
                    (int) offset,
                    rs.getString("part")),
            offset + 1, // SQL substring is 1-based
            maxChars,
            id);
    return rows.stream().findFirst();
  }

  private static Page<Summary> page(List<Summary> rows, int limit, long start, boolean keyset) {
    if (rows.size() <= limit) {
      return new Page<>(rows, -1);
    }
    List<Summary> items = List.copyOf(rows.subList(0, limit));
    long next = keyset ? items.get(items.size() - 1).id() : start + limit;
    return new Page<>(items, next);
  }
}
