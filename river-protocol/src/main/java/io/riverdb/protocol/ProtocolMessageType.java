package io.riverdb.protocol;

/** Ordered v4 request operations supported by the River network boundary. */
public enum ProtocolMessageType {
  HELLO(1, false),
  AUTHENTICATE(2, true),
  OPEN_SESSION(3, false),
  EXECUTE(4, true),
  BEGIN_QUERY(5, true),
  FETCH(6, false),
  CLOSE_QUERY(7, false),
  CLOSE_SESSION(8, false),
  PREPARE(9, true),
  EXECUTE_PREPARED(10, true),
  BEGIN_PREPARED_QUERY(11, true),
  CLOSE_PREPARED(12, true),
  PREPARE_PROGRAM(13, true),
  EXECUTE_PROGRAM(14, true),
  CLOSE_PROGRAM(15, true);

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
      case 9 -> PREPARE;
      case 10 -> EXECUTE_PREPARED;
      case 11 -> BEGIN_PREPARED_QUERY;
      case 12 -> CLOSE_PREPARED;
      case 13 -> PREPARE_PROGRAM;
      case 14 -> EXECUTE_PROGRAM;
      case 15 -> CLOSE_PROGRAM;
      default -> null;
    };
  }
}
