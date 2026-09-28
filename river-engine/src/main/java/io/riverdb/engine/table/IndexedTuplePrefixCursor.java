package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreeLeafEntry;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.btree.TupleBTree;
import io.riverdb.storage.btree.TupleBTreeCursor;
import io.riverdb.storage.btree.BTreeStructuralLimits;
import io.riverdb.storage.btree.TupleBTreeTreeWorkspace;
import java.nio.ByteBuffer;

/** Reusable cursor state for one prefix probe. */
final class IndexedTuplePrefixCursor {
  private final IndexedTupleProbePageProvider provider;
  private final TupleBTree tree;
  private final TupleBTreeCursor cursor = new TupleBTreeCursor();
  private final TupleBTreeLeafEntry entry = new TupleBTreeLeafEntry();
  private final TupleBTreeTreeWorkspace workspace;

  IndexedTuplePrefixCursor(IndexedPageSet pages) {
    provider = new IndexedTupleProbePageProvider(pages);
    tree = new TupleBTree(provider, 1, null);
    int height = BTreeStructuralLimits.MAXIMUM_LEVELS;
    workspace = new TupleBTreeTreeWorkspace(
        ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES),
        ByteBuffer.allocate(TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES),
        new int[height], new int[height], new int[height]);
  }

  StatusCode probe(
      long visibleCommitSequence,
      int rootPageId, int nextPageId, long keyId, long schemaId,
      TupleShape indexShape, TupleShape prefixShape,
      ByteBuffer key, int offset, int length,
      IndexedTupleIntentJournal intents, long excludedRowId,
      IndexedTupleProbeResult result) {
    StatusCode status = provider.configure(
        rootPageId, keyId, nextPageId, 1, visibleCommitSequence);
    if (status.isOk()) status = tree.configure(provider, schemaId, indexShape);
    if (status.isOk()) {
      status = cursor.openPrefix(tree, key, offset, length, prefixShape, workspace);
    }
    while (status.isOk()) {
      status = cursor.next(entry);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      long rowId = entry.logicalRowId();
      if (rowId != excludedRowId && (intents == null || !intents.deletesPrefixRow(
          keyId, prefixShape, key, offset, length, rowId))) {
        result.set(rowId);
        break;
      }
    }
    StatusCode closed = cursor.close();
    return status.isOk() ? closed : status;
  }
}
