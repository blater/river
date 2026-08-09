package io.riverdb.platform.schedule;

/**
 * Stable work identities used to reserve capacity and make scheduling policy explicit.
 */
public enum SchedulingClass {
  JOURNAL(0),
  RECOVERY(0),
  CONSENSUS(0),
  FOREGROUND_SQL(1),
  MAINTENANCE(2),
  STATE_SYNC(2),
  BEST_EFFORT(3);

  private final int priority;

  SchedulingClass(int priority) {
    this.priority = priority;
  }

  public int priority() {
    return priority;
  }

  public boolean isCritical() {
    return priority == 0;
  }
}
