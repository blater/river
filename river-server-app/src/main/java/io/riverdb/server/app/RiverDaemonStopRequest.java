package io.riverdb.server.app;


/** Bounded, checksummed codec for the cooperative riverd stop request. */
final class RiverDaemonStopRequest {
  static final int MAX_RECORD_BYTES = 8192;
  static final String REQUEST_NAME = "stop.request";
  static final String STAGE_PREFIX = ".stop-request-";
  static final String STAGE_SUFFIX = ".stage";
  static final String ACCEPTED_PREFIX = ".stop-accepted-";
  static final String FORMAT = "riverd-stop-request-v1";

  private RiverDaemonStopRequest() {
  }

  static String encode(long high, long low, String ownerNonce, String requestNonce,
      String runtimeChecksum, long requestedAt) {
    return RiverDaemonRecordEnvelope.record(java.util.List.of(
        "format=" + FORMAT,
        "database-incarnation-high=" + high,
        "database-incarnation-low=" + low,
        "owner-nonce=" + ownerNonce,
        "request-nonce=" + requestNonce,
        "runtime-record-sha256=" + runtimeChecksum,
        "requested-at-epoch-millis=" + requestedAt));
  }

  static Record parse(byte[] bytes) {
    if (bytes == null || bytes.length == 0 || bytes.length > MAX_RECORD_BYTES) return null;
    RiverDaemonRecordEnvelope.Envelope envelope =
        RiverDaemonRecordEnvelope.decodePadded(bytes, 7, FORMAT);
    if (envelope == null) return null;
    String[] fields = envelope.fields;
    try {
      String highText = value(fields[1], "database-incarnation-high=");
      String lowText = value(fields[2], "database-incarnation-low=");
      String atText = value(fields[6], "requested-at-epoch-millis=");
      long high = Long.parseLong(highText);
      long low = Long.parseLong(lowText);
      long at = Long.parseLong(atText);
      if (!Long.toString(high).equals(highText) || !Long.toString(low).equals(lowText)
          || !Long.toString(at).equals(atText)) return null;
      String owner = value(fields[3], "owner-nonce=");
      String request = value(fields[4], "request-nonce=");
      String runtime = value(fields[5], "runtime-record-sha256=");
      if (at < 0 || !owner.matches("[0-9a-f]{32}") || !request.matches("[0-9a-f]{32}")
          || !runtime.matches("[0-9a-f]{64}")) return null;
      return new Record(high, low, owner, request, runtime, at);
    } catch (NumberFormatException failure) {
      return null;
    }
  }

  static String value(String field, String key) {
    return field.startsWith(key) ? field.substring(key.length()) : "";
  }

  static final class Record {
    final long high;
    final long low;
    final String ownerNonce;
    final String requestNonce;
    final String runtimeChecksum;
    final long requestedAt;

    Record(long high, long low, String ownerNonce, String requestNonce, String runtimeChecksum,
        long requestedAt) {
      this.high = high;
      this.low = low;
      this.ownerNonce = ownerNonce;
      this.requestNonce = requestNonce;
      this.runtimeChecksum = runtimeChecksum;
      this.requestedAt = requestedAt;
    }
  }

}
