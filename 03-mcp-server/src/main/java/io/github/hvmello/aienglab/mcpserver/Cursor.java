package io.github.hvmello.aienglab.mcpserver;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * An opaque pagination cursor: {@code base64url(payload) "." base64url(HMAC-SHA256(payload))}.
 *
 * <p>The payload names the tool that issued it and what it is bound to (the search query, the
 * document id), so a cursor copied from another tool or another query is rejected like an edited
 * one. The client can neither build nor change a cursor.
 */
public record Cursor(String kind, String binding, long position) {

  public static final String LIST = "list";
  public static final String SEARCH = "search";
  public static final String READ = "read";

  /** Thrown for every cursor that is not exactly one this server issued for this call. */
  public static final class InvalidCursorException extends RuntimeException {
    public InvalidCursorException() {
      super("invalid cursor");
    }
  }

  public String encode(byte[] key) {
    String payload = kind + "|" + binding + "|" + position;
    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
    Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
    return enc.encodeToString(bytes) + "." + enc.encodeToString(hmac(key, bytes));
  }

  public static Cursor decode(
      String token, byte[] key, String expectedKind, String expectedBinding) {
    try {
      int dot = token.indexOf(".");
      if (dot <= 0 || dot == token.length() - 1 || token.length() > 2048) {
        throw new InvalidCursorException();
      }
      Base64.Decoder dec = Base64.getUrlDecoder();
      byte[] payload = dec.decode(token.substring(0, dot));
      byte[] mac = dec.decode(token.substring(dot + 1));
      if (!MessageDigest.isEqual(mac, hmac(key, payload))) {
        throw new InvalidCursorException();
      }
      String[] parts = new String(payload, StandardCharsets.UTF_8).split("\\|", 3);
      if (parts.length != 3
          || !parts[0].equals(expectedKind)
          || !parts[1].equals(expectedBinding)) {
        throw new InvalidCursorException();
      }
      long position = Long.parseLong(parts[2]);
      if (position < 0) {
        throw new InvalidCursorException();
      }
      return new Cursor(parts[0], parts[1], position);
    } catch (IllegalArgumentException e) {
      // bad base64 or number
      throw new InvalidCursorException();
    }
  }

  private static byte[] hmac(byte[] key, byte[] data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return mac.doFinal(data);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }
}
