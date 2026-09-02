package io.riverdb.protocol;

import io.riverdb.engine.api.IsolationLevel;

/** Stable wire vocabulary for transaction isolation. */
final class ProtocolIsolationLevelCodec {
  private static final int READ_COMMITTED = 1;
  private static final int REPEATABLE_READ = 2;
  private static final int SERIALIZABLE = 3;

  private ProtocolIsolationLevelCodec() { }

  static int encode(IsolationLevel isolationLevel) {
    if (isolationLevel == null) return 0;
    return switch (isolationLevel) {
      case READ_COMMITTED -> READ_COMMITTED;
      case REPEATABLE_READ -> REPEATABLE_READ;
      case SERIALIZABLE -> SERIALIZABLE;
    };
  }

  static IsolationLevel decode(int wireCode) {
    return switch (wireCode) {
      case READ_COMMITTED -> IsolationLevel.READ_COMMITTED;
      case REPEATABLE_READ -> IsolationLevel.REPEATABLE_READ;
      case SERIALIZABLE -> IsolationLevel.SERIALIZABLE;
      default -> null;
    };
  }
}
