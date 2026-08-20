package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.sql.SqlCommand;

/** Owns canonical UTF-8 literals retained by one nested predicate plan. */
final class SqlNestedPredicateText {
  private final byte[] bytes = new byte[SqlCommand.MAXIMUM_TEXT_BYTES];
  private int length;

  StatusCode capture(SqlCommand source, SqlNestedPredicatePlan plan) {
    reset();
    for (int leaf = 0; leaf < plan.count(); leaf++) {
      length = Math.max(length, literalEnd(source, plan, leaf));
      for (int member = 0; member < plan.memberCount(leaf); member++) {
        if (!plan.memberNull(leaf, member)
            && isVarchar(plan.memberDescriptor(leaf, member))) {
          length = Math.max(
              length, textEnd(source, plan.memberValue(leaf, member)));
        }
      }
    }
    if (length > bytes.length) return StatusCode.RESOURCE_EXHAUSTED;
    for (int leaf = 0; leaf < plan.count(); leaf++) {
      copyLiterals(source, plan, leaf);
      for (int member = 0; member < plan.memberCount(leaf); member++) {
        if (!plan.memberNull(leaf, member)
            && isVarchar(plan.memberDescriptor(leaf, member))) {
          copyText(source, plan.memberValue(leaf, member));
        }
      }
    }
    return StatusCode.OK;
  }

  private static int literalEnd(
      SqlCommand source, SqlNestedPredicatePlan plan, int leaf) {
    int end = !plan.isValueNull(leaf) && isVarchar(plan.typeDescriptor(leaf))
        ? textEnd(source, plan.value(leaf)) : 0;
    if (!plan.isBetween(leaf)) return end;
    if (!plan.isLowerNull(leaf) && isVarchar(plan.lowerDescriptor(leaf))) {
      end = Math.max(end, textEnd(source, plan.lowerInclusive(leaf)));
    }
    return !plan.isUpperNull(leaf) && isVarchar(plan.upperDescriptor(leaf))
        ? Math.max(end, textEnd(source, plan.upperExclusive(leaf))) : end;
  }

  private void copyLiterals(
      SqlCommand source, SqlNestedPredicatePlan plan, int leaf) {
    if (!plan.isValueNull(leaf) && isVarchar(plan.typeDescriptor(leaf))) {
      copyText(source, plan.value(leaf));
    }
    if (!plan.isBetween(leaf)) return;
    if (!plan.isLowerNull(leaf) && isVarchar(plan.lowerDescriptor(leaf))) {
      copyText(source, plan.lowerInclusive(leaf));
    }
    if (!plan.isUpperNull(leaf) && isVarchar(plan.upperDescriptor(leaf))) {
      copyText(source, plan.upperExclusive(leaf));
    }
  }

  void reset() {
    for (int index = 0; index < length; index++) bytes[index] = 0;
    length = 0;
  }

  int byteLength(long handle) {
    int offset = (int) (handle >>> 32);
    int byteLength = (int) handle;
    return offset >= 0 && byteLength >= 0 && offset <= length - byteLength
        ? byteLength : -1;
  }

  byte byteAt(long handle, int index) {
    int byteLength = byteLength(handle);
    return index >= 0 && index < byteLength
        ? bytes[(int) (handle >>> 32) + index] : 0;
  }

  private void copyText(SqlCommand source, long handle) {
    int byteLength = source.textByteLength(handle);
    if (byteLength < 0) return;
    int offset = (int) (handle >>> 32);
    for (int index = 0; index < byteLength; index++) {
      bytes[offset + index] = source.textByteAt(handle, index);
    }
  }

  private static int textEnd(SqlCommand source, long handle) {
    int byteLength = source.textByteLength(handle);
    return byteLength < 0 ? 0 : (int) (handle >>> 32) + byteLength;
  }

  private static boolean isVarchar(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }
}
