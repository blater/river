package io.riverdb.platform.fault;

/** Caller-owned result slot used by {@link FaultPointRegistry#register}. */
public final class FaultPointSlot {
  private FaultPoint value;

  public FaultPoint value() {
    return value;
  }

  void set(FaultPoint value) {
    this.value = value;
  }
}
