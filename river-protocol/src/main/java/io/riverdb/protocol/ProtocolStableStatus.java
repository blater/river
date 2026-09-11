package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;

/** Decodes the protocol's stable numeric status contract. */
final class ProtocolStableStatus {
  private ProtocolStableStatus() { }

  static StatusCode fromCode(int code) {
    return switch (code) {
      case 0 -> StatusCode.OK; case 1000 -> StatusCode.RETRY; case 1001 -> StatusCode.FENCED;
      case 1002 -> StatusCode.CLOSED; case 2000 -> StatusCode.CANCELLED;
      case 3000 -> StatusCode.INVALID_EXTERNAL_INPUT; case 3001 -> StatusCode.CARDINALITY_VIOLATION;
      case 3002 -> StatusCode.NUMERIC_VALUE_OUT_OF_RANGE; case 3003 -> StatusCode.CHECK_VIOLATION;
      case 3004 -> StatusCode.UNIQUE_VIOLATION; case 3005 -> StatusCode.FOREIGN_KEY_VIOLATION;
      case 3006 -> StatusCode.DATATYPE_MISMATCH; case 3007 -> StatusCode.ACCESS_DENIED;
      case 3008 -> StatusCode.DIVISION_BY_ZERO; case 3009 -> StatusCode.INVALID_DATETIME_FORMAT;
      case 3010 -> StatusCode.DATETIME_FIELD_OVERFLOW; case 3011 -> StatusCode.INVALID_TIME_ZONE_DISPLACEMENT;
      case 3012 -> StatusCode.STRING_DATA_RIGHT_TRUNCATION; case 3013 -> StatusCode.FEATURE_NOT_SUPPORTED;
      case 3014 -> StatusCode.PARAMETER_COUNT_MISMATCH; case 3015 -> StatusCode.PROGRAM_STALE;
      case 4000 -> StatusCode.CONFLICT;
      case 4001 -> StatusCode.NOT_OWNER; case 4002 -> StatusCode.DEADLOCK;
      case 5000 -> StatusCode.RESOURCE_EXHAUSTED;
      case 5001 -> StatusCode.QUERY_TOO_COMPLEX; case 6000 -> StatusCode.TIMEOUT;
      case 7000 -> StatusCode.IO_FAILURE; case 8000 -> StatusCode.CORRUPTION;
      case 9000 -> StatusCode.INVARIANT_BROKEN; default -> null;
    };
  }
}
