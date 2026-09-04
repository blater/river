package io.riverdb.storage.btree;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleBTreePageHeader;
import io.riverdb.format.btree.TupleBTreePageValidationProof;
import java.nio.ByteBuffer;

/** Owns complete validation and cached-proof admission for tuple-page borrows. */
final class TupleBTreePageAdmission {
  private TupleBTreePageAdmission() { }

  static StatusCode validate(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      int expectedType, TupleBTreeWorkspace workspace) {
    if (workspace == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = TupleBTreePageCodec.validateForRead(
        page, start, schemaId, shape, workspace.header, workspace.validation);
    if (status.isOk() && expectedType > 0 && workspace.header.type() != expectedType) {
      return revoke(
          workspace.validation, workspace.header, StatusCode.INVALID_EXTERNAL_INPUT);
    }
    return status;
  }

  static StatusCode validate(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      int expectedType, TupleBTreeWorkspace workspace,
      TupleBTreePageProvider provider, TupleBTreePageReference reference) {
    if (workspace == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (provider == null && reference == null) {
      return validate(page, start, schemaId, shape, expectedType, workspace);
    }
    return validate(
        page, start, schemaId, shape, expectedType, workspace.header, provider, reference);
  }

  static StatusCode validate(
      ByteBuffer page, int start, long schemaId, TupleShape shape,
      int expectedType, TupleBTreePageHeader header,
      TupleBTreePageProvider provider, TupleBTreePageReference reference) {
    TupleBTreePageValidationProof proof = reference == null
        ? null : reference.validation();
    if (!exactBorrow(page, start, provider, reference)) {
      if (proof != null) proof.reset();
      if (header != null) header.reset();
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    proof.reset();
    StatusCode restored = provider.restorePageValidation(
        reference, schemaId, shape == null ? 0 : shape.descriptorHash(),
        expectedType, proof);
    if (!restored.isOk() && restored != StatusCode.CONFLICT) return restored;
    if (restored.isOk()) {
      return readAuthenticatedHeader(page, start, expectedType, proof, header);
    }
    StatusCode status = TupleBTreePageCodec.validateForRead(
        page, start, schemaId, shape, header, proof);
    if (status.isOk() && expectedType > 0 && header.type() != expectedType) {
      return revoke(proof, header, StatusCode.INVALID_EXTERNAL_INPUT);
    }
    if (!status.isOk()) return status;
    status = provider.rememberPageValidation(
        reference, schemaId, shape.descriptorHash(), header.type(), proof);
    return status.isOk() ? status : revoke(proof, header, status);
  }

  private static boolean exactBorrow(
      ByteBuffer page, int start,
      TupleBTreePageProvider provider, TupleBTreePageReference reference) {
    return provider != null && reference != null && reference.isAttached()
        && reference.page() == page && reference.start() == start;
  }

  private static StatusCode readAuthenticatedHeader(
      ByteBuffer page, int start, int expectedType,
      TupleBTreePageValidationProof proof, TupleBTreePageHeader header) {
    StatusCode status = TupleBTreePageCodec.readValidatedHeader(
        page, start, proof, header);
    if (!status.isOk()) return status;
    return expectedType <= 0 || header.type() == expectedType
        ? StatusCode.OK
        : revoke(proof, header, StatusCode.INVALID_EXTERNAL_INPUT);
  }

  private static StatusCode revoke(
      TupleBTreePageValidationProof proof,
      TupleBTreePageHeader header,
      StatusCode status) {
    proof.reset();
    header.reset();
    return status;
  }
}
