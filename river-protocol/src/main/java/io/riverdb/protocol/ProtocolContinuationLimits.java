package io.riverdb.protocol;

/** Arithmetic bounds for logical payloads expanded into physical frames. */
final class ProtocolContinuationLimits {
  private ProtocolContinuationLimits() { }

  static int wireBytes(int logicalBytes, int maximumLogicalBytes) {
    if (logicalBytes <= ProtocolFrameCodec.MAXIMUM_PAYLOAD_BYTES
        || logicalBytes > maximumLogicalBytes) return 0;
    int frames = (logicalBytes + ProtocolResponseSegmenter.DATA_BYTES - 1)
        / ProtocolResponseSegmenter.DATA_BYTES;
    return logicalBytes + frames
        * (ProtocolFrameCodec.HEADER_BYTES + ProtocolResponseSegmenter.SEGMENT_BYTES);
  }

  static int maximumWireBytes(int maximumLogicalBytes) {
    int frames = (maximumLogicalBytes + ProtocolResponseSegmenter.DATA_BYTES - 1)
        / ProtocolResponseSegmenter.DATA_BYTES;
    return maximumLogicalBytes + frames
        * (ProtocolFrameCodec.HEADER_BYTES + ProtocolResponseSegmenter.SEGMENT_BYTES);
  }
}
