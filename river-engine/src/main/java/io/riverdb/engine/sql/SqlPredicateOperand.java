package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.BoundedByteSource;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.SqlValueAccess;

/** Reusable predicate operand; stored UTF-8 is borrowed only during synchronous evaluation. */
final class SqlPredicateOperand {
  private final BoundedByteSource arrayView = new BoundedByteSource() {
    @Override public int length() { return borrowedArray == null ? 0 : borrowedArray.length; }
    @Override public byte getByte(int offset) { return borrowedArray[offset]; }
  };
  private char[] text;
  private long high;
  private long value;
  private int descriptor;
  private int textLength;
  private boolean nullValue;
  private BoundedByteSource borrowedText;
  private byte[] borrowedArray;
  private char[] borrowedChars;
  private int borrowedOffset;
  private int borrowedLength;
  private int borrowedCharacters = -1;

  void capture(SqlRowExpressionEvaluator evaluator) {
    eraseText();
    high = evaluator.resultHighValue();
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
    borrowedText = null;
    borrowedArray = null;
    borrowedChars = null;
    borrowedCharacters = -1;
    value = 0;
    high = 0;
    descriptor = 0;
    textLength = 0;
    nullValue = false;
  }

  void prepareText() {
    ensureText(0);
  }

  void setTextCharacters(
      char[] source, int offset, int length, int typeDescriptor) {
    clear();
    descriptor = typeDescriptor;
    ensureText(length);
    for (int index = 0; index < length; index++) text[index] = source[offset + index];
    textLength = length;
  }

  StatusCode setText(
      SqlValueAccess source, int column, int typeDescriptor) {
    clear();
    descriptor = typeDescriptor;
    int offset = source.textByteOffsetAt(column);
    int length = source.textByteLengthAt(column);
    BoundedByteSource bytes = source.textSource(column);
    if (offset < 0 || length < 0 || bytes == null
        || offset > bytes.length() - length) return fail(StatusCode.CORRUPTION);
    borrowedText = bytes;
    borrowedOffset = offset;
    borrowedLength = length;
    return StatusCode.OK;
  }

  StatusCode setText(
      SqlBlockRow source, int column, int typeDescriptor) {
    if (source.borrowedValues() != null) {
      return setText(source.borrowedValues(), column, typeDescriptor);
    }
    if (source.hasUtf8(column)) {
      return setUtf8(source.ownedUtf8(column), 0, source.utf8Length(column), typeDescriptor);
    }
    clear();
    descriptor = typeDescriptor;
    int length = source.textLength(column);
    if (length < 0) return fail(StatusCode.CORRUPTION);
    borrowedChars = source.text(column);
    if (borrowedChars == null && length > 0 || borrowedChars != null
        && length > borrowedChars.length) return fail(StatusCode.CORRUPTION);
    textLength = length;
    return StatusCode.OK;
  }

  void setNull(int typeDescriptor) {
    clear();
    descriptor = typeDescriptor;
    nullValue = true;
  }

  void setValue(long fixedValue, int typeDescriptor, boolean isNull) {
    setValue(fixedValue >> 63, fixedValue, typeDescriptor, isNull);
  }

  void setValue(
      long highValue, long fixedValue, int typeDescriptor, boolean isNull) {
    clear();
    high = highValue;
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
    borrowedArray = source;
    borrowedOffset = offset;
    borrowedLength = length;
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
    if (text != null && borrowedText == null && borrowedArray == null
        && borrowedChars == null) {
      for (int index = 0; index < textLength; index++) text[index] = 0;
    }
    textLength = 0;
  }

  private void ensureText(int length) {
    if (text == null || text.length < length) text = new char[Math.max(510, length)];
  }

  boolean hasBorrowedUtf8() { return borrowedText != null || borrowedArray != null; }

  StatusCode copyTextTo(int column, SqlScanRowResult result) {
    if (borrowedText != null) {
      return result.setUtf8At(column, borrowedText, borrowedOffset, borrowedLength);
    }
    if (borrowedArray != null) {
      return result.setUtf8At(column, arrayView, borrowedOffset, borrowedLength);
    }
    StatusCode status = result.beginTextAt(column, textLength());
    for (int index = 0; status.isOk() && index < textLength(); index++) {
      result.setTextCharacterAt(column, index, textCharacter(index));
    }
    return status.isOk() ? result.finishTextAt(column) : status;
  }

  StatusCode copyTextTo(int column, SqlBlockRow result) {
    if (borrowedText != null) {
      return result.setUtf8(column, borrowedText, borrowedOffset, borrowedLength);
    }
    if (borrowedArray != null) {
      return result.setUtf8(column, borrowedArray, borrowedOffset, borrowedLength);
    }
    StatusCode status = result.prepareText(column);
    if (!status.isOk()) return status;
    char[] target = result.text(column);
    if (target == null) return result.status();
    for (int index = 0; index < textLength(); index++) {
      target[index] = textCharacter(index);
    }
    result.setTextLength(column, textLength());
    return StatusCode.OK;
  }
  int borrowedByteLength() { return borrowedLength; }
  int borrowedByteAt(int index) {
    return borrowedArray == null
        ? Byte.toUnsignedInt(borrowedText.getByte(borrowedOffset + index))
        : Byte.toUnsignedInt(borrowedArray[borrowedOffset + index]);
  }

  long value() { return value; }
  long highValue() { return high; }
  int descriptor() { return descriptor; }
  boolean nullValue() { return nullValue; }
  int textLength() {
    if (!hasBorrowedUtf8()) return textLength;
    if (borrowedCharacters >= 0) return borrowedCharacters;
    int characters = 0;
    for (int index = 0; index < borrowedLength;) {
      int first = borrowedByteAt(index);
      int width = first < 0x80 ? 1 : first < 0xe0 ? 2 : first < 0xf0 ? 3 : 4;
      characters += width == 4 ? 2 : 1;
      index += width;
    }
    borrowedCharacters = characters;
    return characters;
  }
  char textCharacter(int target) {
    if (borrowedChars != null) return borrowedChars[target];
    if (!hasBorrowedUtf8()) return text[target];
    int character = 0;
    for (int index = 0; index < borrowedLength;) {
      int first = borrowedByteAt(index);
      int width = first < 0x80 ? 1 : first < 0xe0 ? 2 : first < 0xf0 ? 3 : 4;
      int scalar = width == 1 ? first : first & (0x7f >> width);
      for (int byteIndex = 1; byteIndex < width; byteIndex++) {
        scalar = scalar << 6 | borrowedByteAt(index + byteIndex) & 0x3f;
      }
      if (width < 4) {
        if (character == target) return (char) scalar;
        character++;
      } else {
        if (character == target) return Character.highSurrogate(scalar);
        if (character + 1 == target) return Character.lowSurrogate(scalar);
        character += 2;
      }
      index += width;
    }
    return 0;
  }
}
