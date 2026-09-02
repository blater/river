package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Replaces one renameable secondary-key name while retaining its durable key identity. */
final class RelationalDescriptorIndexRenameChange {
  private static final String PRIMARY_NAME = "PRIMARY";
  private static final String INTERNAL_PREFIX = "_river_";
  private final RelationalDescriptorKeyCopy keys = new RelationalDescriptorKeyCopy();

  StatusCode build(
      TableDescriptor current,
      CharSequence currentName,
      CharSequence renamedName,
      TableDescriptor.Result result,
      StatusDetail detail) {
    if (result != null) result.reset();
    if (detail != null) detail.reset();
    if (current == null || currentName == null || renamedName == null || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (protectedPrimary(current.primaryKey(), currentName)
        || protectedPrimary(current.primaryKey(), renamedName)
        || internal(currentName) || internal(renamedName)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int renamed = current.findSecondaryKey(currentName);
    if (renamed < 0) return StatusCode.CONFLICT;
    if (current.findSecondaryKey(renamedName) >= 0) return StatusCode.CONFLICT;
    KeyDescriptor candidate = current.secondaryKeyAt(renamed);
    if (candidate.kind() != KeyDescriptor.KIND_SECONDARY) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    KeyDescriptor[] secondary;
    KeyDescriptor[] foreign;
    try {
      secondary = new KeyDescriptor[current.secondaryKeyCount()];
      foreign = new KeyDescriptor[current.foreignKeyCount()];
      for (int index = 0; index < secondary.length; index++) {
        secondary[index] = index == renamed
            ? keys.key(candidate, current.columns(), renamedName, detail)
            : current.secondaryKeyAt(index);
      }
      for (int index = 0; index < foreign.length; index++) {
        foreign[index] = current.foreignKeyAt(index);
      }
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    if (secondary[renamed] == null) return RelationalDescriptorKeyCopy.status(detail);
    return TableDescriptor.createProposedSuccessor(
        current.tableId(), current.rowLayoutId(), current.catalogGeneration(),
        current.columns(), current.primaryKey(), secondary, foreign, result, detail);
  }

  private static boolean protectedPrimary(KeyDescriptor primary, CharSequence name) {
    return primary != null && (primary.matchesName(name) || same(PRIMARY_NAME, name));
  }

  private static boolean internal(CharSequence name) {
    return name != null && name.length() >= INTERNAL_PREFIX.length()
        && startsWith(name, INTERNAL_PREFIX);
  }

  private static boolean same(CharSequence left, CharSequence right) {
    return left != null && right != null && left.length() == right.length()
        && startsWith(left, right);
  }

  private static boolean startsWith(CharSequence value, CharSequence prefix) {
    for (int index = 0; index < prefix.length(); index++) {
      if (Character.toUpperCase(value.charAt(index))
          != Character.toUpperCase(prefix.charAt(index))) return false;
    }
    return true;
  }
}
