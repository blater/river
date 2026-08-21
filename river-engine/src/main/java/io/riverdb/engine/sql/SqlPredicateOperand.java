package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;

/** Reusable primitive/text result for one evaluated predicate operand. */
final class SqlPredicateOperand {
  private char[] text;
  private long value;
  private int descriptor;
  private int textLength;
  private boolean nullValue;

  void capture(SqlRowExpressionEvaluator evaluator) {
    eraseText();
    value = evaluator.resultValue();
    descriptor = evaluator.resultDescriptor();
    nullValue = evaluator.resultNull();
    textLength = 0;
    if (!nullValue
        && SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      int length = evaluator.resultTextLength();
      ensureText(length);
      for (int index = 0; index < length; index++) {
        text[index] = evaluator.resultTextCharacter(index);
      }
      textLength = length;
    }
  }

  void clear() {
    eraseText();
    value = 0;
    descriptor = 0;
    textLength = 0;
    nullValue = false;
  }

  void prepareText() {
    ensureText(0);
  }

  void copyFrom(SqlPredicateOperand source) {
    clear();
    value = source.value;
    descriptor = source.descriptor;
    nullValue = source.nullValue;
    if (!nullValue
        && SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      ensureText(source.textLength);
      for (int index = 0; index < source.textLength; index++) {
        text[index] = source.text[index];
      }
      textLength = source.textLength;
    }
  }

  void setTextCharacters(
      char[] source, int offset, int length, int typeDescriptor) {
    clear();
    descriptor = typeDescriptor;
    ensureText(length);
    for (int index = 0; index < length; index++) text[index] = source[offset + index];
    textLength = length;
  }

  void setNull(int typeDescriptor) {
    clear();
    descriptor = typeDescriptor;
    nullValue = true;
  }

  void setValue(long fixedValue, int typeDescriptor, boolean isNull) {
    clear();
    value = fixedValue;
    descriptor = typeDescriptor;
    nullValue = isNull;
  }

  StatusCode setUtf8(
      byte[] source, int offset, int length, int typeDescriptor) {
    clear();
    if (source == null || offset < 0 || length < 0
        || offset > source.length - length) return StatusCode.CORRUPTION;
    descriptor = typeDescriptor;
    ensureText(length);
    int decoded = SqlUtf8ArrayDecoder.decode(source, offset, length, text);
    if (decoded == SqlUtf8ArrayDecoder.CORRUPT) return fail(StatusCode.CORRUPTION);
    if (decoded == SqlUtf8ArrayDecoder.EXHAUSTED) {
      return fail(StatusCode.CORRUPTION);
    }
    textLength = decoded;
    return StatusCode.OK;
  }

  private StatusCode fail(StatusCode status) {
    if (text != null) {
      for (int index = 0; index < text.length; index++) text[index] = 0;
    }
    clear();
    return status;
  }

  private void eraseText() {
    if (text != null) {
      for (int index = 0; index < textLength; index++) text[index] = 0;
    }
    textLength = 0;
  }

  private void ensureText(int length) {
    if (text == null) text = new char[510];
  }

  long value() { return value; }
  int descriptor() { return descriptor; }
  boolean nullValue() { return nullValue; }
  int textLength() { return textLength; }
  char textCharacter(int index) { return text[index]; }
}
