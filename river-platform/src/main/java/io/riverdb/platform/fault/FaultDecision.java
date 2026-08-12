package io.riverdb.platform.fault;

/** Caller-owned mutable result for an allocation-free fault decision. */
public final class FaultDecision {
  private FaultAction action = FaultAction.NONE;
  private long argument;

  public FaultAction action() {
    return action;
  }

  /** Action-specific argument, normally a byte limit, capacity, or XOR mask. */
  public long argument() {
    return argument;
  }

  public void set(FaultAction action, long argument) {
    this.action = action;
    this.argument = argument;
  }

  public void reset() {
    action = FaultAction.NONE;
    argument = 0;
  }
}
