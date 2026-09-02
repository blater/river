package io.riverdb.jdbc;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.ParameterSet;
import java.sql.SQLException;

/** Reusable primitive and text storage behind one JDBC statement's bindings. */
final class RiverJdbcParameterStore {
  private static final byte UNSET = 0;
  private static final byte NULL = 1;
  private static final byte FIXED = 2;
  private static final byte TEXT = 3;
  private static final byte DECIMAL_128 = 4;

  private final int[] descriptors;
  private final long[] values;
  private final long[] decimalHighs;
  private final String[] texts;
  private final byte[] kinds;
  private final ParameterSet current;

  RiverJdbcParameterStore(int count) {
    descriptors = new int[count];
    values = new long[count];
    decimalHighs = new long[count];
    texts = new String[count];
    kinds = new byte[count];
    current = new ParameterSet(count, ParameterSet.MAXIMUM_TEXT_BYTES);
  }

  void setNull(int index, int descriptor) {
    publish(index, descriptor, 0, 0, null, NULL);
  }

  void setFixed(int index, int descriptor, long value) {
    publish(index, descriptor, 0, value, null, FIXED);
  }

  void setDecimal128(int index, int descriptor, long high, long low) {
    publish(index, descriptor, high, low, null, DECIMAL_128);
  }

  void setText(int index, int descriptor, String value) {
    publish(index, descriptor, 0, 0, value, TEXT);
  }

  ParameterSet parameters() throws SQLException {
    fill(current);
    return current;
  }

  ParameterSet snapshot() throws SQLException {
    requireComplete();
    int textBytes = 0;
    for (int index = 0; index < kinds.length; index++) {
      if (kinds[index] == TEXT) {
        int bytes = Utf8Text.encodedLength(texts[index], Utf8Text.MAXIMUM_SCALARS);
        if (bytes < 0 || textBytes > ParameterSet.MAXIMUM_TEXT_BYTES - bytes) {
          throw JdbcExceptions.failure(StatusCode.RESOURCE_EXHAUSTED, "snapshot parameters");
        }
        textBytes += bytes;
      }
    }
    ParameterSet snapshot = new ParameterSet(kinds.length, textBytes);
    fill(snapshot);
    return snapshot;
  }

  void clear() {
    for (int index = 0; index < kinds.length; index++) {
      kinds[index] = UNSET;
      descriptors[index] = 0;
      values[index] = 0;
      decimalHighs[index] = 0;
      texts[index] = null;
    }
    current.reset();
  }

  private void fill(ParameterSet target) throws SQLException {
    requireComplete();
    target.reset();
    for (int index = 0; index < kinds.length; index++) {
      StatusCode status = switch (kinds[index]) {
        case NULL -> target.appendNull(descriptors[index]);
        case FIXED -> target.appendFixed(descriptors[index], values[index]);
        case DECIMAL_128 -> target.appendDecimal128(
            SqlTypeDescriptor.parameterOne(descriptors[index]),
            SqlTypeDescriptor.parameterTwo(descriptors[index]),
            decimalHighs[index], values[index]);
        case TEXT -> target.appendText(descriptors[index], texts[index]);
        default -> StatusCode.PARAMETER_COUNT_MISMATCH;
      };
      if (!status.isOk()) {
        throw JdbcExceptions.failure(status, "bind parameter " + (index + 1));
      }
    }
  }

  private void requireComplete() throws SQLException {
    for (int index = 0; index < kinds.length; index++) {
      if (kinds[index] == UNSET) {
        throw JdbcExceptions.failure(
            StatusCode.PARAMETER_COUNT_MISMATCH, "bind parameter " + (index + 1));
      }
    }
  }

  private void publish(
      int index, int descriptor, long high, long value, String text, byte kind) {
    descriptors[index] = descriptor;
    decimalHighs[index] = high;
    values[index] = value;
    texts[index] = text;
    kinds[index] = kind;
  }
}
