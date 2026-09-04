package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Child routing over one validated tuple internal page. */
final class TupleBTreeInternalSearch {
  private TupleBTreeInternalSearch() { }

  static int childForKey(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength, TupleBTreeWorkspace workspace) {
    if (workspace == null || !TupleKeyCodec.matchesPhysicalIndexKey(
        key, keyOffset, keyLength, shape)) return 0;
    StatusCode status = TupleBTreePageAdmission.validate(
        page, start, schemaId, shape, TupleBTreePageCodec.TYPE_INTERNAL, workspace);
    if (!status.isOk()) return 0;
    return childForKeyValidated(page, start, key, keyOffset, keyLength, workspace);
  }

  static int childForKeyValidated(
      ByteBuffer page, int start, ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace) {
    int insertion = TupleBTreePageSupport.lowerBoundInternal(
        page, start, key, keyOffset, keyLength, workspace);
    if (insertion < workspace.header.entryCount()) {
      TupleBTreePageSupport.readInternal(page, start, insertion, workspace);
      if (TupleKeyCodec.compare(
          page, start + workspace.internal.keyOffset(), workspace.internal.keyLength(),
          key, keyOffset, keyLength) == 0) insertion++;
    }
    if (insertion == 0) return workspace.header.firstChildPageId();
    TupleBTreePageSupport.readInternal(page, start, insertion - 1, workspace);
    return workspace.internal.rightChildPageId();
  }
}
