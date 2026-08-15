package io.riverdb.base.type;

/** Stable catalog kinds for literal and statement-resolved column defaults. */
public final class SqlDefaultKind {
  public static final int NONE = 0;
  public static final int LITERAL = 1;
  public static final int CURRENT_DATE = 2;
  public static final int CURRENT_TIMESTAMP = 3;
  public static final int LOCALTIME = 4;
  public static final int LOCALTIMESTAMP = 5;

  private SqlDefaultKind() {
  }

  public static boolean compatible(int kind, int descriptor) {
    int type = SqlTypeDescriptor.typeId(descriptor);
    return switch (kind) {
      case CURRENT_DATE -> type == SqlTypeDescriptor.TYPE_ID_DATE;
      case CURRENT_TIMESTAMP ->
          type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP_WITH_TIME_ZONE;
      case LOCALTIME -> type == SqlTypeDescriptor.TYPE_ID_TIME;
      case LOCALTIMESTAMP -> type == SqlTypeDescriptor.TYPE_ID_TIMESTAMP;
      default -> false;
    };
  }

  public static boolean isCurrent(int kind) {
    return kind >= CURRENT_DATE && kind <= LOCALTIMESTAMP;
  }
}
