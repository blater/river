package io.riverdb.engine.sql;

import io.riverdb.base.text.PackedText;
import io.riverdb.sql.SqlJoinChain;

/** Compact physical-strategy metadata and dynamic ANALYZE operator resolution. */
final class SqlJoinStrategyPlan {
  private static final long FALLBACK = PackedText.pack("fallbk");
  private static final long FALL_LEFT = PackedText.pack("fbleft");
  private static final long HASH = PackedText.pack("hash");
  private static final long HASH_LEFT = PackedText.pack("hleft");
  private static final long MERGE = PackedText.pack("merge");
  private static final long MERGE_LEFT = PackedText.pack("mleft");
  private static final long INDEX = PackedText.pack("index");
  private static final long JOIN = PackedText.pack("join");
  private static final long LEFT = PackedText.pack("left");
  private static final long LOOKUP = PackedText.pack("lookup");
  private static final long TABLE = PackedText.pack("table");
  private final byte[] strategies = new byte[SqlJoinChainPlan.MAXIMUM_STEPS];
  private final boolean[] left = new boolean[SqlJoinChainPlan.MAXIMUM_STEPS];

  void clear(int step) {
    strategies[step] = SqlJoinStrategy.NESTED_LOOP;
    left[step] = false;
  }

  void set(int step, int strategy, boolean leftStage) {
    strategies[step] = (byte) strategy;
    left[step] = leftStage;
  }

  int directInner(BoundSqlStatement bound, int stage) {
    return bound.joinStrategy(stage) != SqlJoinStrategy.NESTED_LOOP
        ? bound.joinStrategyInnerColumn(stage) : bound.joinAccessInnerColumn(stage);
  }

  int directAccess(BoundSqlStatement bound, int stage, int inner) {
    int strategy = bound.joinStrategy(stage);
    if (strategy == SqlJoinStrategy.HASH || inner < 0) return 0;
    if (strategy == SqlJoinStrategy.MERGE) return inner == 0 ? 0 : 1;
    boolean indexed = inner == 0 || bound.joinRole(stage + 1).hasIndexOn(inner);
    if (!indexed) return 0;
    return inner == 0 || bound.joinRole(stage + 1).hasUniqueIndexOn(inner) ? 2 : 1;
  }

  long access(int strategy, int access) {
    if (strategy == SqlJoinStrategy.HASH) return TABLE;
    if (strategy == SqlJoinStrategy.MERGE) return access == 1 ? INDEX : TABLE;
    return access == 2 ? LOOKUP : access == 1 ? INDEX : TABLE;
  }

  long published(int strategy, boolean leftStage) {
    if (strategy == SqlJoinStrategy.HASH) return leftStage ? HASH_LEFT : HASH;
    if (strategy == SqlJoinStrategy.MERGE) return leftStage ? MERGE_LEFT : MERGE;
    return leftStage ? LEFT : JOIN;
  }

  long operator(
      int step,
      int stage,
      long planned,
      boolean analyzed,
      SqlJoinChainSource source) {
    if (Byte.toUnsignedInt(strategies[step]) != SqlJoinStrategy.HASH
        || !analyzed || source == null) {
      return planned;
    }
    if (!source.stageFallback(stage)) return planned;
    return left[step] ? FALL_LEFT : FALLBACK;
  }
}
