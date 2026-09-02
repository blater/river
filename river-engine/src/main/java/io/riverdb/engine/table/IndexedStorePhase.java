package io.riverdb.engine.table;

/** Mechanical representation of the indexed store's mutually exclusive operation modes. */
final class IndexedStorePhase {
  enum Mode {
    IDLE,
    STAGED,
    HYBRID_PREFLIGHT,
    HYBRID_ENCODING,
    HYBRID_FORCED,
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

  boolean commitGroupActive() {
    return mode == Mode.HYBRID_PREFLIGHT
        || mode == Mode.HYBRID_ENCODING
        || mode == Mode.HYBRID_FORCED;
  }

  boolean beginStaged() {
    return transition(Mode.IDLE, Mode.STAGED);
  }

  boolean beginVacuumApply() {
    return transition(Mode.IDLE, Mode.VACUUM_APPLY);
  }

  boolean beginHybridPreflight() {
    return transition(Mode.IDLE, Mode.HYBRID_PREFLIGHT);
  }

  boolean beginHybridEncoding() {
    return transition(Mode.HYBRID_PREFLIGHT, Mode.HYBRID_ENCODING);
  }

  boolean markHybridForced() {
    return transition(Mode.HYBRID_ENCODING, Mode.HYBRID_FORCED);
  }

  boolean hybridEncoding() {
    return mode == Mode.HYBRID_ENCODING || mode == Mode.HYBRID_FORCED;
  }

  boolean hybridForced() {
    return mode == Mode.HYBRID_FORCED;
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
