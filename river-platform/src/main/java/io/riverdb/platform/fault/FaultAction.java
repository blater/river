package io.riverdb.platform.fault;

/** Actions understood by deterministic test providers. Production injectors always choose NONE. */
public enum FaultAction {
  NONE,
  CRASH,
  SHORT_READ,
  SHORT_WRITE,
  PARTIAL_WRITE,
  FORCE_FAILURE,
  DISK_FULL,
  TORN_WRITE,
  /** Mutates returned bytes without reporting an error, modeling undetected media corruption. */
  CORRUPT_READ,
  /** Mutates returned bytes and reports {@code CORRUPTION}, modeling checksum detection. */
  DETECTED_CORRUPTION,
  /** Withholds start at a before point or completion at an after point. */
  DELAY,
  CANCEL,
  RESTART;

  /** Fail-closed compatibility matrix shared by script admission and fault consumers. */
  public boolean isCompatibleWith(FaultOperation operation) {
    return isCompatibleWith(operation, FaultBoundary.BEFORE);
  }

  /** Fail-closed compatibility including the operation's mutation boundary. */
  public boolean isCompatibleWith(FaultOperation operation, FaultBoundary boundary) {
    if (operation == null || boundary == null) {
      return false;
    }
    if (boundary == FaultBoundary.AFTER) {
      return switch (this) {
        case NONE, CRASH, CANCEL -> true;
        case RESTART -> operation != FaultOperation.CRASH
            && operation != FaultOperation.SCHEDULE
            && operation != FaultOperation.RUN_TASK;
        case DELAY -> isBoundaryOperation(operation);
        case SHORT_READ, SHORT_WRITE, PARTIAL_WRITE, FORCE_FAILURE, DISK_FULL,
            TORN_WRITE, CORRUPT_READ, DETECTED_CORRUPTION -> false;
      };
    }
    return switch (this) {
      case NONE, CRASH, CANCEL -> true;
      case DELAY -> isBoundaryOperation(operation);
      case RESTART -> operation != FaultOperation.CRASH
          && operation != FaultOperation.SCHEDULE
          && operation != FaultOperation.RUN_TASK;
      case SHORT_READ, CORRUPT_READ, DETECTED_CORRUPTION ->
          operation == FaultOperation.READ
              || operation == FaultOperation.REOPEN_VERIFY
              || operation == FaultOperation.DIRECTORY_FILE_READ;
      case SHORT_WRITE, PARTIAL_WRITE, TORN_WRITE ->
          operation == FaultOperation.WRITE
              || operation == FaultOperation.TEMP_WRITE
              || operation == FaultOperation.DIRECTORY_FILE_WRITE;
      case FORCE_FAILURE -> operation == FaultOperation.FORCE
          || operation == FaultOperation.TEMP_FORCE
          || operation == FaultOperation.DIRECTORY_FORCE
          || operation == FaultOperation.DIRECTORY_FILE_FORCE;
      case DISK_FULL ->
          operation == FaultOperation.WRITE
              || operation == FaultOperation.FORCE
              || operation == FaultOperation.TEMP_CREATE
              || operation == FaultOperation.TEMP_WRITE
              || operation == FaultOperation.TEMP_FORCE
              || operation == FaultOperation.DIRECTORY_FORCE
              || operation == FaultOperation.DIRECTORY_CREATE
              || operation == FaultOperation.FILE_CREATE
              || operation == FaultOperation.DIRECTORY_FILE_WRITE
              || operation == FaultOperation.DIRECTORY_FILE_FORCE;
    };
  }

  private static boolean isBoundaryOperation(FaultOperation operation) {
    return operation == FaultOperation.TEMP_CREATE
        || operation == FaultOperation.TEMP_WRITE
        || operation == FaultOperation.TEMP_FORCE
        || operation == FaultOperation.REPLACE
        || operation == FaultOperation.DIRECTORY_FORCE
        || operation == FaultOperation.REOPEN_VERIFY
        || operation == FaultOperation.DIRECTORY_CREATE
        || operation == FaultOperation.DIRECTORY_LIST
        || operation == FaultOperation.FILE_CREATE
        || operation == FaultOperation.FILE_RENAME
        || operation == FaultOperation.FILE_REMOVE
        || operation == FaultOperation.NAMED_TRUNCATE
        || operation == FaultOperation.DIRECTORY_FILE_FORCE
        || operation == FaultOperation.DIRECTORY_REOPEN;
  }
}
