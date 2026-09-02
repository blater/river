package io.riverdb.engine.runtime.materialized;

/** One reusable, bounded two-revolution second-chance scan. */
final class SqlMaterializedPageClock {
  private int cursor;
  private long remaining;

  void begin(int hand, int frameCount) {
    cursor = hand;
    remaining = (long) frameCount * 2;
  }

  int next(
      SqlMaterializedPageFrames frames, long owner, boolean ownerReservation) {
    while (remaining-- > 0) {
      int frame = cursor;
      cursor = cursor + 1 == frames.count() ? 0 : cursor + 1;
      if (!eligible(frames, frame, owner, ownerReservation)) continue;
      if (frames.referenced[frame]) {
        frames.referenced[frame] = false;
        continue;
      }
      return frame;
    }
    return -1;
  }

  int cursor() { return cursor; }

  private boolean eligible(
      SqlMaterializedPageFrames frames, int frame, long owner,
      boolean ownerReservation) {
    byte state = frames.state[frame];
    if (frames.pins[frame] != 0 || frames.selected[frame] || frames.failed[frame]
        || state != SqlMaterializedPageFrames.FREE
            && state != SqlMaterializedPageFrames.READY) return false;
    long reserved = frames.reservationOwners[frame];
    return ownerReservation ? reserved == owner : reserved == 0;
  }
}
