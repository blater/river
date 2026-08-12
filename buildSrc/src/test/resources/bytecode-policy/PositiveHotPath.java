package fixture.bytecode;

final class PositiveHotPath {
  private PositiveHotPath() {
  }

  static int publish(long[] slots, int mask, long position, long value) {
    slots[(int) position & mask] = value;
    return 0;
  }

  static int copy(byte[] source, byte[] target, int length) {
    System.arraycopy(source, 0, target, 0, length);
    return length;
  }
}
