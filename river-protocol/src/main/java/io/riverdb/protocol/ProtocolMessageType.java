package io.riverdb.protocol;

/** Ordered v3 request operations supported by the River network boundary. */
public enum ProtocolMessageType {
  HELLO(1, false),
  AUTHENTICATE(2, true),
  OPEN_SESSION(3, false),
  EXECUTE(4, true),
  BEGIN_QUERY(5, true),
  FETCH(6, false),
  CLOSE_QUERY(7, false),
  CLOSE_SESSION(8, false);

  private final int wireCode;
  private final boolean payloadRequired;

  ProtocolMessageType(int code, boolean requiresPayload) {
    wireCode = code;
    payloadRequired = requiresPayload;
  }

  public int wireCode() {
    return wireCode;
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
