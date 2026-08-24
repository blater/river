package io.riverdb.format.btree;

import java.nio.ByteBuffer;

/** Routes tuple-key validation to structural and declared-shape checks. */
final class TupleKeyValidation {
  private TupleKeyValidation() { }

  static boolean validate(ByteBuffer source, int offset, int length) {
    return TupleKeyStructureValidation.validate(source, offset, length);
  }

  static boolean matchesShape(ByteBuffer source, int offset, int length, int arity,
      int first, int second, int third, int fourth) {
    return TupleKeyShapeValidation.matches(source, offset, length, arity,
        first, second, third, fourth);
  }
}
