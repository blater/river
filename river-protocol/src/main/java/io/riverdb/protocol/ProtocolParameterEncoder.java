package io.riverdb.protocol;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.ParameterSet;
import io.riverdb.engine.api.TransactionProgramArguments;
import java.nio.ByteBuffer;

/** Encodes the shared typed-parameter representation used by request families. */
final class ProtocolParameterEncoder {
  private static final int NULL_FLAG = 1;

  private ProtocolParameterEncoder() {}

  static int valueBytes(ParameterSet parameters, int index) {
    int descriptor = parameters.typeDescriptorAt(index);
    if (parameters.isNull(index)) {
      return descriptor == 0 || SqlTypeDescriptor.isValid(descriptor) ? 0 : -1;
    }
    if (!SqlTypeDescriptor.isValid(descriptor)) {
      return -1;
    }
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        ? parameters.textLengthAt(index) : ProtocolDecimal128.bytes(descriptor);
  }

  static int writeParameter(
      ByteBuffer target, int output, ParameterSet parameters, int index) {
    int descriptor = parameters.typeDescriptorAt(index);
    boolean nullValue = parameters.isNull(index);
    int valueBytes = valueBytes(parameters, index);
    output = ProtocolValueHeader.write(
        target, output, descriptor, nullValue ? NULL_FLAG : 0, valueBytes);
    if (nullValue) {
      return output;
    }
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      if (ProtocolDecimal128.isWide(descriptor)) {
        target.putLong(output, parameters.decimalUnscaledHighAt(index));
        output += Long.BYTES;
      }
      target.putLong(output, parameters.valueAt(index));
      return output + Long.BYTES;
    }
    for (int byteIndex = 0; byteIndex < valueBytes; byteIndex++) {
      target.put(output++, parameters.textByteAt(index, byteIndex));
    }
    return output;
  }

  static int valueBytes(TransactionProgramArguments parameters, int index) {
    if (!parameters.isSet(index)) return -1;
    int descriptor = parameters.typeDescriptorAt(index);
    if (parameters.isNull(index)) {
      return descriptor == 0 || SqlTypeDescriptor.isValid(descriptor) ? 0 : -1;
    }
    if (!SqlTypeDescriptor.isValid(descriptor)) return -1;
    return SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR
        ? ProtocolSqlTextEncoder.argumentBytes(parameters, index)
        : ProtocolDecimal128.bytes(descriptor);
  }

  static int writeParameter(
      ByteBuffer target, int output, TransactionProgramArguments parameters, int index) {
    int descriptor = parameters.typeDescriptorAt(index);
    boolean nullValue = parameters.isNull(index);
    int valueBytes = valueBytes(parameters, index);
    output = ProtocolValueHeader.write(
        target, output, descriptor, nullValue ? NULL_FLAG : 0, valueBytes);
    if (nullValue) return output;
    if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
      if (ProtocolDecimal128.isWide(descriptor)) {
        target.putLong(output, parameters.highValueAt(index));
        output += Long.BYTES;
      }
      target.putLong(output, parameters.valueAt(index));
      return output + Long.BYTES;
    }
    return ProtocolSqlTextEncoder.writeArgument(target, output, parameters, index);
  }
}
