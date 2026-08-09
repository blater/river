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
  /** Withholds completion without applying the operation at a before boundary. */
  DELAY,
  CANCEL,
  RESTART;

  /** Fail-closed compatibility matrix shared by script admission and fault consumers. */
  public boolean isCompatibleWith(FaultOperation operation) {
    if (operation == null) {
      return false;
    }
    return switch (this) {
      case NONE, CRASH, CANCEL, DELAY -> true;
      case RESTART -> operation != FaultOperation.CRASH
          && operation != FaultOperation.SCHEDULE
          && operation != FaultOperation.RUN_TASK;
      case SHORT_READ, CORRUPT_READ, DETECTED_CORRUPTION ->
          operation == FaultOperation.READ || operation == FaultOperation.REOPEN_VERIFY;
      case SHORT_WRITE, PARTIAL_WRITE, TORN_WRITE ->
          operation == FaultOperation.WRITE || operation == FaultOperation.TEMP_WRITE;
      case FORCE_FAILURE -> operation == FaultOperation.FORCE
          || operation == FaultOperation.TEMP_FORCE
          || operation == FaultOperation.DIRECTORY_FORCE;
      case DISK_FULL ->
          operation == FaultOperation.WRITE
              || operation == FaultOperation.FORCE
              || operation == FaultOperation.TEMP_CREATE
              || operation == FaultOperation.TEMP_WRITE
              || operation == FaultOperation.TEMP_FORCE
              || operation == FaultOperation.DIRECTORY_FORCE;
    };
  }
}
