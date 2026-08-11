package io.riverdb.protocol;

/** Ordered v1 request operations supported by the first River network slice. */
public enum ProtocolMessageType {
  HELLO(1, false),
  OPEN_SESSION(2, false),
  EXECUTE(3, true),
  BEGIN_QUERY(4, true),
  FETCH(5, false),
  CLOSE_QUERY(6, false),
  CLOSE_SESSION(7, false);

  private final int wireCode;
  private final boolean textPayload;

  ProtocolMessageType(int code, boolean hasTextPayload) {
    wireCode = code;
    textPayload = hasTextPayload;
  }

  public int wireCode() {
    return wireCode;
  }

  public boolean hasTextPayload() {
    return textPayload;
  }

  public static ProtocolMessageType fromWireCode(int code) {
    return switch (code) {
      case 1 -> HELLO;
      case 2 -> OPEN_SESSION;
      case 3 -> EXECUTE;
      case 4 -> BEGIN_QUERY;
      case 5 -> FETCH;
      case 6 -> CLOSE_QUERY;
      case 7 -> CLOSE_SESSION;
      default -> null;
    };
  }
}
