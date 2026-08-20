package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import java.nio.ByteBuffer;

/** Owns proportional row offsets and one canonical UTF-8 sort-key index. */
final class SqlBlockRowIndex {
  private static final int WARM_TEXT_BYTES = 64 * 1_024;

  private final TextView text = new TextView();
  private final SqlBlockRowIndexStorage storage = new SqlBlockRowIndexStorage();
  private ByteBuffer keyText;
  private int sortKey = -1;
  private boolean descending;
  private boolean textKey;

  void begin(int keyColumn, boolean descendingOrder, boolean varcharKey) {
    sortKey = keyColumn;
    descending = descendingOrder;
    textKey = varcharKey;
    if (keyText != null) keyText.clear();
  }

  StatusCode ensure(int required, long otherRetained) {
    return storage.ensure(
        required, sortKey >= 0, textKey,
        otherRetained + (keyText == null ? 0 : keyText.capacity()));
  }

  StatusCode captureKey(
      SqlBlockRow source, int ordinal, long otherRetained) {
    if (sortKey < 0) return StatusCode.OK;
    storage.keyNull(ordinal, source.nullValue(sortKey));
    if (storage.keyNull(ordinal)) return StatusCode.OK;
    if (!textKey) {
      storage.key(ordinal, source.value(sortKey));
      return StatusCode.OK;
    }
    text.set(source, sortKey);
    int required = Utf8Text.encodedLength(text, Utf8Text.MAXIMUM_SCALARS);
    if (required < 0 || required > Short.MAX_VALUE
        || !ensureText(required, otherRetained)) {
      text.clear();
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    storage.text(ordinal, keyText.position(), required);
    int encoded = Utf8Text.encode(text, Utf8Text.MAXIMUM_SCALARS, keyText);
    text.clear();
    return encoded == required ? StatusCode.OK : StatusCode.INVARIANT_BROKEN;
  }

  void setRecord(int ordinal, long offset, int length) {
    storage.setRecord(ordinal, offset, length);
  }

  void finish(int count) {
    if (sortKey < 0) return;
    for (int root = count / 2 - 1; root >= 0; root--) sift(root, count);
    for (int end = count - 1; end > 0; end--) {
      int swap = storage.order(0);
      storage.order(0, storage.order(end));
      storage.order(end, swap);
      sift(0, end);
    }
  }

  int stored(int position) { return storage.order(position); }
  long offset(int ordinal) { return storage.offset(ordinal); }
  int length(int ordinal) { return storage.length(ordinal); }
  long retainedBytes() {
    return storage.retainedBytes() + (keyText == null ? 0 : keyText.capacity());
  }

  void eraseSlot(int index) {
    storage.eraseSlot(index);
  }

  void rollbackText(int position) {
    if (keyText == null) return;
    int end = keyText.position();
    for (int index = position; index < end; index++) keyText.put(index, (byte) 0);
    keyText.position(position);
  }

  int textPosition() { return keyText == null ? 0 : keyText.position(); }

  void close(int used) {
    storage.close(used);
    erase(keyText);
    text.clear();
    if (keyText != null && keyText.capacity() > WARM_TEXT_BYTES) keyText = null;
  }

  private boolean ensureText(int required, long otherRetained) {
    long maximum = Math.max(
        keyText == null ? 0 : keyText.capacity(),
        SqlBlockRowStore.MAXIMUM_BYTES - otherRetained - storage.retainedBytes());
    if (keyText == null) {
      if (required > maximum) return false;
      keyText = ByteBuffer.allocateDirect((int) Math.min(WARM_TEXT_BYTES, maximum));
    }
    if (keyText.remaining() >= required) return true;
    int needed = keyText.position() + required;
    if (needed > maximum) return false;
    int capacity = keyText.capacity();
    while (capacity < needed) capacity = (int) Math.min(maximum, capacity * 2L);
    ByteBuffer grown = ByteBuffer.allocateDirect(capacity);
    int used = keyText.position();
    keyText.flip();
    grown.put(keyText);
    erase(keyText, used);
    keyText = grown;
    return true;
  }

  private void sift(int root, int count) {
    int current = root;
    while (current * 2 + 1 < count) {
      int child = current * 2 + 1;
      if (child + 1 < count
          && compare(storage.order(child), storage.order(child + 1)) < 0) child++;
      if (compare(storage.order(current), storage.order(child)) >= 0) return;
      int swap = storage.order(current);
      storage.order(current, storage.order(child));
      storage.order(child, swap);
      current = child;
    }
  }

  private int compare(int left, int right) {
    int compared;
    if (storage.keyNull(left) != storage.keyNull(right)) {
      compared = storage.keyNull(left) ? -1 : 1;
    } else if (storage.keyNull(left)) compared = 0;
    else if (textKey) compared = compareText(left, right);
    else compared = Long.compare(storage.key(left), storage.key(right));
    if (compared != 0) return descending ? -compared : compared;
    return Integer.compare(left, right);
  }

  private int compareText(int left, int right) {
    int leftLength = storage.textLength(left);
    int rightLength = storage.textLength(right);
    int common = Math.min(leftLength, rightLength);
    for (int index = 0; index < common; index++) {
      int compared = Integer.compare(
          Byte.toUnsignedInt(keyText.get(storage.textOffset(left) + index)),
          Byte.toUnsignedInt(keyText.get(storage.textOffset(right) + index)));
      if (compared != 0) return compared;
    }
    return Integer.compare(leftLength, rightLength);
  }

  private static void erase(ByteBuffer buffer) {
    if (buffer == null) return;
    erase(buffer, buffer.position());
  }

  private static void erase(ByteBuffer buffer, int length) {
    buffer.clear();
    for (int index = 0; index < Math.min(length, buffer.capacity()); index++) {
      buffer.put(index, (byte) 0);
    }
    buffer.clear();
  }

  private static final class TextView implements CharSequence {
    private SqlBlockRow row;
    private int column;
    void set(SqlBlockRow source, int sourceColumn) {
      row = source;
      column = sourceColumn;
    }
    void clear() { row = null; column = 0; }
    @Override public int length() { return row.textLength(column); }
    @Override public char charAt(int index) { return row.textCharacter(column, index); }
    @Override public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }
  }
}
