package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Rebinds placeholder physical suffixes after logical-row-ID reservation. */
final class RelationalDescriptorTupleDeltaRowBinding {
  StatusCode bind(RelationalDescriptorTupleDeltaPlan plan, long logicalRowId) {
    if (plan == null || logicalRowId <= 0 || plan.kind() == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; index < plan.keyCount(); index++) {
      if (plan.beforeLengthAt(index) > 0) {
        put(plan.bytes(), plan.beforeOffsetAt(index), plan.beforeLengthAt(index), logicalRowId);
      }
      if (plan.afterLengthAt(index) > 0
          && (plan.beforeLengthAt(index) == 0
              || plan.afterOffsetAt(index) != plan.beforeOffsetAt(index))) {
        put(plan.bytes(), plan.afterOffsetAt(index), plan.afterLengthAt(index), logicalRowId);
      }
    }
    return StatusCode.OK;
  }

  private static void put(
      ByteBuffer target, int offset, int length, long value) {
    int cursor = offset + length - TupleKeyCodec.LOGICAL_ROW_ID_BYTES;
    for (int shift = 56; shift >= 0; shift -= 8) {
      target.put(cursor++, (byte) (value >>> shift));
    }
  }
}
