package io.riverdb.engine.table;

/** Mechanical representation of the indexed store's mutually exclusive operation modes. */
final class IndexedStorePhase {
  enum Mode {
    IDLE,
    STAGED,
    PREPARED_PREFLIGHT,
    PREPARED_ENCODING,
    PREPARED_FORCED,
    VACUUM_APPLY
  }

  private Mode mode = Mode.IDLE;

  Mode mode() {
    return mode;
  }

  boolean isIdle() {
    return mode == Mode.IDLE;
  }

  boolean operationActive() {
    return mode == Mode.STAGED || mode == Mode.VACUUM_APPLY;
  }

  boolean vacuumOperationActive() {
    return mode == Mode.VACUUM_APPLY;
  }

  boolean preparedInsertGroupActive() {
    return mode == Mode.PREPARED_PREFLIGHT
        || mode == Mode.PREPARED_ENCODING
        || mode == Mode.PREPARED_FORCED;
  }

  boolean preparedInsertEncoding() {
    return mode == Mode.PREPARED_ENCODING || mode == Mode.PREPARED_FORCED;
  }

  boolean preparedInsertForced() {
    return mode == Mode.PREPARED_FORCED;
  }

  boolean beginStaged() {
    return transition(Mode.IDLE, Mode.STAGED);
  }

  boolean beginPreparedPreflight() {
    return transition(Mode.IDLE, Mode.PREPARED_PREFLIGHT);
  }

  boolean beginPreparedEncoding() {
    return transition(Mode.PREPARED_PREFLIGHT, Mode.PREPARED_ENCODING);
  }

  boolean markPreparedForced() {
    return transition(Mode.PREPARED_ENCODING, Mode.PREPARED_FORCED);
  }

  boolean beginVacuumApply() {
    return transition(Mode.IDLE, Mode.VACUUM_APPLY);
  }

  void reset() {
    mode = Mode.IDLE;
  }

  private boolean transition(Mode expected, Mode next) {
    if (mode != expected) {
      return false;
    }
    mode = next;
    return true;
  }
}
