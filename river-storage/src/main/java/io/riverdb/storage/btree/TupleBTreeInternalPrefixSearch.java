package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Routes to the leftmost child that may contain a leading tuple prefix. */
final class TupleBTreeInternalPrefixSearch {
  private TupleBTreeInternalPrefixSearch() { }

  static int child(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer prefix, int offset, int length, int parts,
      TupleBTreeWorkspace workspace) {
    StatusCode status = TupleBTreePageAdmission.validate(
        page, start, schemaId, shape, TupleBTreePageCodec.TYPE_INTERNAL, workspace);
    if (!status.isOk()) return 0;
    return childValidated(page, start, prefix, offset, length, parts, workspace);
  }

  static int childValidated(
      ByteBuffer page, int start, ByteBuffer prefix, int offset, int length, int parts,
      TupleBTreeWorkspace workspace) {
    int low = 0;
    int high = workspace.header.entryCount();
    while (low < high) {
      int middle = (low + high) >>> 1;
      TupleBTreePageSupport.readInternal(page, start, middle, workspace);
      int comparison = TupleKeyCodec.comparePrefix(
          page, start + workspace.internal.keyOffset(), workspace.internal.keyLength(),
          prefix, offset, length, parts);
      if (comparison < 0) low = middle + 1;
      else high = middle;
    }
    if (low == 0) return workspace.header.firstChildPageId();
    TupleBTreePageSupport.readInternal(page, start, low - 1, workspace);
    return workspace.internal.rightChildPageId();
  }

  static int upperChild(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer prefix, int offset, int length, int parts,
      TupleBTreeWorkspace workspace) {
    StatusCode status = TupleBTreePageAdmission.validate(
        page, start, schemaId, shape, TupleBTreePageCodec.TYPE_INTERNAL, workspace);
    if (!status.isOk()) return 0;
    return upperChildValidated(page, start, prefix, offset, length, parts, workspace);
  }

  static int upperChildValidated(
      ByteBuffer page, int start, ByteBuffer prefix, int offset, int length, int parts,
      TupleBTreeWorkspace workspace) {
    int low = 0;
    int high = workspace.header.entryCount();
    while (low < high) {
      int middle = (low + high) >>> 1;
      TupleBTreePageSupport.readInternal(page, start, middle, workspace);
      int comparison = TupleKeyCodec.comparePrefix(
          page, start + workspace.internal.keyOffset(), workspace.internal.keyLength(),
          prefix, offset, length, parts);
      if (comparison <= 0) low = middle + 1;
      else high = middle;
    }
    if (low == 0) return workspace.header.firstChildPageId();
    TupleBTreePageSupport.readInternal(page, start, low - 1, workspace);
    return workspace.internal.rightChildPageId();
  }
}
