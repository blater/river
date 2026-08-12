package io.riverdb.format.control;

/** Caller-owned output for control-file decoding. */
public final class ControlFileDecodeResult {
  private ControlFile controlFile;

  public ControlFile controlFile() {
    return controlFile;
  }

  public void set(ControlFile value) {
    controlFile = value;
  }

  public void reset() {
    controlFile = null;
  }
}
