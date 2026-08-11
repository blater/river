package io.riverdb.protocol;

/** Ordered v1 request operations supported by the first River network slice. */
public enum ProtocolMessageType {
  HELLO(1, false, false),
  AUTHENTICATE(2, true, false),
  OPEN_SESSION(3, false, false),
  EXECUTE(4, true, true),
  BEGIN_QUERY(5, true, true),
  FETCH(6, false, false),
  CLOSE_QUERY(7, false, false),
  CLOSE_SESSION(8, false, false);

  private final int wireCode;
  private final boolean payloadRequired;
  private final boolean textPayload;

  ProtocolMessageType(int code, boolean requiresPayload, boolean hasTextPayload) {
    wireCode = code;
    payloadRequired = requiresPayload;
    textPayload = hasTextPayload;
  }

  public int wireCode() {
    return wireCode;
  }

  public boolean hasTextPayload() {
    return textPayload;
  }

  public boolean requiresPayload() {
    return payloadRequired;
  }

  public static ProtocolMessageType fromWireCode(int code) {
    return switch (code) {
      case 1 -> HELLO;
      case 2 -> AUTHENTICATE;
      case 3 -> OPEN_SESSION;
      case 4 -> EXECUTE;
      case 5 -> BEGIN_QUERY;
      case 6 -> FETCH;
      case 7 -> CLOSE_QUERY;
      case 8 -> CLOSE_SESSION;
      default -> null;
    };
  }
}
