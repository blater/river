package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.storage.btree.TupleBTreeScanBounds;

/** Borrowed descriptor values admitted and copied by one caller-owned scan cursor. */
public final class RelationalDescriptorIndexBounds {
  private KeyDescriptor key;
  private SqlValueBuffer lower;
  private SqlValueBuffer upper;
  private int lowerParts;
  private int upperParts;
  private int direction;
  private boolean lowerInclusive;
  private boolean upperInclusive;

  public StatusCode set(
      KeyDescriptor descriptor, SqlValueBuffer low, int lowParts, boolean lowInclusive,
      SqlValueBuffer high, int highParts, boolean highInclusive, int scanDirection) {
    if (descriptor == null || descriptor.keyId() <= 0
        || lowParts < 0 || lowParts > descriptor.partCount()
        || highParts < 0 || highParts > descriptor.partCount()
        || lowParts > 0 != (low != null) || highParts > 0 != (high != null)
        || scanDirection != TupleBTreeScanBounds.FORWARD
            && scanDirection != TupleBTreeScanBounds.REVERSE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    key = descriptor;
    lower = low;
    upper = high;
    lowerParts = lowParts;
    upperParts = highParts;
    lowerInclusive = lowInclusive;
    upperInclusive = highInclusive;
    direction = scanDirection;
    return StatusCode.OK;
  }

  KeyDescriptor key() { return key; }
  SqlValueBuffer lower() { return lower; }
  SqlValueBuffer upper() { return upper; }
  int lowerParts() { return lowerParts; }
  int upperParts() { return upperParts; }
  int direction() { return direction; }
  boolean lowerInclusive() { return lowerInclusive; }
  boolean upperInclusive() { return upperInclusive; }
}
