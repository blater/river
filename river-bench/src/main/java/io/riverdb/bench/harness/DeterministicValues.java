package io.riverdb.bench.harness;

/** Stateless deterministic values keep output independent of buffering and generation order. */
final class DeterministicValues {
  private DeterministicValues() {
  }

  static long value(long seed, long sequence, long lane) {
    long mixed = seed + 0x9e3779b97f4a7c15L * (sequence + 1)
        + 0xd1b54a32d192ed03L * (lane + 1);
    mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
    mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
    return mixed ^ (mixed >>> 31);
  }

  static long bounded(long seed, long sequence, long lane, long bound) {
    return Long.remainderUnsigned(value(seed, sequence, lane), bound);
  }
}
