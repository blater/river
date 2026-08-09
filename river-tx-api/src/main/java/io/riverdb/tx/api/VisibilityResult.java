package io.riverdb.tx.api;

/** Caller-owned visibility output for tuple and index access methods. */
public final class VisibilityResult {
  private VisibilityState state = VisibilityState.HIDDEN;
  private long resolvedCommitSequence;

  public VisibilityResult set(VisibilityState visibilityState, long commitSequence) {
    state = visibilityState;
    resolvedCommitSequence = commitSequence;
    return this;
  }

  public VisibilityState state() {
    return state;
  }

  public long resolvedCommitSequence() {
    return resolvedCommitSequence;
  }

  public boolean isVisible() {
    return state == VisibilityState.VISIBLE || state == VisibilityState.OWN_WRITE;
  }
}
