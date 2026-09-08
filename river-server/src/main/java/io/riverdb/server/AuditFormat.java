package io.riverdb.server;

import io.riverdb.base.error.StatusCode;

/**
 * Concrete binary framing for the accepted a221/ADR0014 field
 * contract is encoded by these widths; they are not runtime configuration.
 */
final class AuditFormat {
  // These fixed widths cover every
  // accepted scalar and the fixed encoded control-name area.
  static final int HEADER_BYTES = 64;
  static final int EVENT_BYTES = 108;
  static final int CONTROL_BYTES = 512;
  static final int SLOT_BYTES = 256;

  int headerBytes() { return HEADER_BYTES; }
  int eventBytes() { return EVENT_BYTES; }
  int controlBytes() { return CONTROL_BYTES; }
  int slotBytes() { return SLOT_BYTES; }

  StatusCode validate(long activeBytes, long pendingBytes) {
    if (activeBytes <= 0 || pendingBytes <= 0
        || activeBytes < (long) HEADER_BYTES + (long) EVENT_BYTES * 2
        || pendingBytes < (long) SLOT_BYTES * 2
        || pendingBytes / SLOT_BYTES > Integer.MAX_VALUE) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    return StatusCode.OK;
  }
}
