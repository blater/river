package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleBTreePageValidationProof;
import io.riverdb.format.btree.TupleKeyCodec;
import java.nio.ByteBuffer;

/** Admits a leaf mutation and prepares its caller-owned mutation scratch. */
final class TupleBTreeLeafMutationPreparation {
  private TupleBTreeLeafMutationPreparation() { }

  static StatusCode prepare(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      ByteBuffer key, int keyOffset, int keyLength,
      TupleBTreeWorkspace workspace,
      TupleBTreePageProvider provider, TupleBTreePageReference reference) {
    if (workspace == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    workspace.mutation.reset();
    if (!TupleBTreePageSupport.validPayload(page, start, true)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!validAccess(page, start, provider, reference)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    TupleBTreePageValidationProof proof = validationProof(provider, reference, workspace);
    StatusCode restored = restoreValidation(
        provider, reference, schemaId, shape, proof);
    if (!restored.isOk() && restored != StatusCode.CONFLICT) return restored;
    StatusCode status = prepareCodec(
        page, start, schemaId, shape, workspace, proof, restored);
    if (!status.isOk()) return status;
    return validateKey(key, keyOffset, keyLength, shape, workspace);
  }

  private static boolean validAccess(
      ByteBuffer page, int start,
      TupleBTreePageProvider provider, TupleBTreePageReference reference) {
    if (provider == null && reference == null) return true;
    return provider != null && reference != null
        && reference.isWritable() && reference.page() == page
        && reference.start() == start;
  }

  private static TupleBTreePageValidationProof validationProof(
      TupleBTreePageProvider provider, TupleBTreePageReference reference,
      TupleBTreeWorkspace workspace) {
    return provider == null && reference == null
        ? workspace.validation : reference.validation();
  }

  private static StatusCode restoreValidation(
      TupleBTreePageProvider provider, TupleBTreePageReference reference,
      long schemaId, TupleShape shape, TupleBTreePageValidationProof proof) {
    if (provider == null && reference == null) return StatusCode.CONFLICT;
    return provider.consumeCanonicalMutationValidation(
        reference, schemaId, shape == null ? 0 : shape.descriptorHash(),
        TupleBTreePageCodec.TYPE_LEAF, proof);
  }

  private static StatusCode prepareCodec(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      TupleBTreeWorkspace workspace, TupleBTreePageValidationProof proof,
      StatusCode restored) {
    if (restored.isOk()) {
      return TupleBTreePageCodec.prepareAuthenticatedLeafMutation(
          page, start, schemaId, shape, workspace.header, proof, workspace.mutation);
    }
    return TupleBTreePageCodec.prepareLeafMutation(
        page, start, schemaId, shape, workspace.header, proof, workspace.mutation);
  }

  private static StatusCode validateKey(
      ByteBuffer key, int keyOffset, int keyLength, TupleShape shape,
      TupleBTreeWorkspace workspace) {
    if (TupleKeyCodec.matchesPhysicalIndexKey(key, keyOffset, keyLength, shape)) {
      return StatusCode.OK;
    }
    workspace.mutation.reset();
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }
}
