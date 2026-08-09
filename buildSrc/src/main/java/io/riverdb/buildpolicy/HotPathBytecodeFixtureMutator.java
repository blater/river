package io.riverdb.buildpolicy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic malformed-class mutations used only by executable policy fixtures. */
public final class HotPathBytecodeFixtureMutator {
  private HotPathBytecodeFixtureMutator() {
  }

  public static byte[] invalidVersion(byte[] source) {
    byte[] result = source.clone();
    writeUnsignedShort(result, 6, 0x7fff);
    return result;
  }

  public static byte[] invalidConstantPoolTag(byte[] source) {
    byte[] result = source.clone();
    result[10] = (byte) 0xff;
    return result;
  }

  public static byte[] truncated(byte[] source) {
    return Arrays.copyOf(source, source.length - 1);
  }

  public static byte[] invalidReservedOpcode(byte[] source, String methodName) {
    byte[] result = source.clone();
    CodeLayout code = layout(result).method(methodName).code();
    result[code.codeStart()] = (byte) 0xcb;
    return result;
  }

  public static byte[] invalidWide(byte[] source, String methodName) {
    byte[] result = source.clone();
    CodeLayout code = layout(result).method(methodName).code();
    require(code.codeLength() >= 2, "wide fixture method is too short");
    result[code.codeStart()] = (byte) 0xc4;
    result[code.codeStart() + 1] = 0;
    return result;
  }

  public static byte[] invalidReferenceReturn(byte[] source, String methodName) {
    byte[] result = source.clone();
    int opcode = findOpcode(result, layout(result).method(methodName).code(), 0xb0);
    result[opcode] = (byte) 0xac;
    return result;
  }

  public static byte[] invalidInvokeInterfaceReserved(
      byte[] source,
      String methodName
  ) {
    byte[] result = source.clone();
    int opcode = findOpcode(result, layout(result).method(methodName).code(), 0xb9);
    result[opcode + 4] = 1;
    return result;
  }

  public static byte[] invalidInvokeDynamicReserved(
      byte[] source,
      String methodName
  ) {
    byte[] result = source.clone();
    int opcode = findOpcode(result, layout(result).method(methodName).code(), 0xba);
    result[opcode + 3] = 1;
    return result;
  }

  public static byte[] invalidNewArrayType(byte[] source, String methodName) {
    byte[] result = source.clone();
    int opcode = findOpcode(result, layout(result).method(methodName).code(), 0xbc);
    result[opcode + 1] = 3;
    return result;
  }

  public static byte[] invalidMultiArrayDimensions(
      byte[] source,
      String methodName
  ) {
    byte[] result = source.clone();
    int opcode = findOpcode(result, layout(result).method(methodName).code(), 0xc5);
    result[opcode + 3] = 0;
    return result;
  }

  public static byte[] invalidTableSwitchBounds(byte[] source, String methodName) {
    byte[] result = source.clone();
    CodeLayout code = layout(result).method(methodName).code();
    int opcode = findOpcode(result, code, 0xaa);
    int header = switchHeader(code, opcode);
    int low = readInt(result, header + 4);
    writeInt(result, header + 8, low - 1);
    return result;
  }

  public static byte[] invalidTableSwitchTarget(byte[] source, String methodName) {
    byte[] result = source.clone();
    CodeLayout code = layout(result).method(methodName).code();
    int opcode = findOpcode(result, code, 0xaa);
    writeInt(result, switchHeader(code, opcode), Integer.MAX_VALUE);
    return result;
  }

  public static byte[] invalidLookupSwitchOrder(byte[] source, String methodName) {
    byte[] result = source.clone();
    CodeLayout code = layout(result).method(methodName).code();
    int opcode = findOpcode(result, code, 0xab);
    int header = switchHeader(code, opcode);
    int pairs = readInt(result, header + 4);
    require(pairs >= 2, "lookup fixture requires at least two pairs");
    int firstMatch = readInt(result, header + 8);
    writeInt(result, header + 16, firstMatch - 1);
    return result;
  }

  public static byte[] invalidCodeAttributeLength(byte[] source, String methodName) {
    byte[] result = source.clone();
    AttributeLayout code = layout(result).method(methodName).code().attribute();
    writeInt(result, code.lengthOffset(), code.length() - 1);
    return result;
  }

  public static byte[] duplicateCodeAttribute(byte[] source, String methodName) {
    ClassLayout layout = layout(source);
    MethodLayout method = layout.method(methodName);
    AttributeLayout code = method.code().attribute();
    int insertion = method.end();
    int duplicateLength = code.end() - code.start();
    byte[] result = new byte[source.length + duplicateLength];
    System.arraycopy(source, 0, result, 0, insertion);
    System.arraycopy(source, code.start(), result, insertion, duplicateLength);
    System.arraycopy(
        source,
        insertion,
        result,
        insertion + duplicateLength,
        source.length - insertion
    );
    writeUnsignedShort(
        result,
        method.attributeCountOffset(),
        readUnsignedShort(source, method.attributeCountOffset()) + 1
    );
    return result;
  }

  public static byte[] invalidBootstrapReference(byte[] source) {
    byte[] result = source.clone();
    AttributeLayout bootstrap = layout(result).classAttribute("BootstrapMethods");
    int count = readUnsignedShort(result, bootstrap.infoStart());
    require(count > 0, "bootstrap fixture has no entries");
    writeUnsignedShort(result, bootstrap.infoStart() + 2, 1);
    return result;
  }

  public static byte[] misleadingInvokeDynamicName(byte[] source) {
    byte[] result = source.clone();
    ClassLayout layout = layout(result);
    Utf8Layout name = layout.utf8("makeConcatWithConstants");
    byte[] replacement = "xxxxxxxxxxxxxxxxxxxxxxx".getBytes(StandardCharsets.UTF_8);
    require(replacement.length == name.length(), "misleading name length changed");
    System.arraycopy(replacement, 0, result, name.bytesStart(), replacement.length);
    return result;
  }

  private static int switchHeader(CodeLayout code, int opcode) {
    int relativeOffset = opcode - code.codeStart();
    int padding = (4 - ((relativeOffset + 1) & 3)) & 3;
    return opcode + 1 + padding;
  }

  private static int findOpcode(byte[] bytes, CodeLayout code, int opcode) {
    for (int index = code.codeStart(); index < code.codeEnd(); index++) {
      if (unsigned(bytes[index]) == opcode) {
        return index;
      }
    }
    throw new IllegalArgumentException(
        "fixture method does not contain opcode 0x" + Integer.toHexString(opcode)
    );
  }

  private static ClassLayout layout(byte[] bytes) {
    require(bytes.length >= 10, "fixture class is truncated");
    require(readInt(bytes, 0) == 0xcafebabe, "fixture is not a class file");
    int constantPoolCount = readUnsignedShort(bytes, 8);
    String[] utf8 = new String[constantPoolCount];
    Utf8Layout[] utf8Layouts = new Utf8Layout[constantPoolCount];
    int offset = 10;
    for (int index = 1; index < constantPoolCount; index++) {
      int tag = unsigned(bytes[offset++]);
      if (tag == 1) {
        int length = readUnsignedShort(bytes, offset);
        int bytesStart = offset + 2;
        utf8[index] = new String(bytes, bytesStart, length, StandardCharsets.UTF_8);
        utf8Layouts[index] = new Utf8Layout(bytesStart, length);
        offset = bytesStart + length;
      } else if (tag == 3 || tag == 4) {
        offset += 4;
      } else if (tag == 5 || tag == 6) {
        offset += 8;
        index++;
      } else if (tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20) {
        offset += 2;
      } else if (tag == 9 || tag == 10 || tag == 11 || tag == 12
          || tag == 17 || tag == 18) {
        offset += 4;
      } else if (tag == 15) {
        offset += 3;
      } else {
        throw new IllegalArgumentException("unsupported fixture constant-pool tag " + tag);
      }
      require(offset <= bytes.length, "fixture constant pool is truncated");
    }

    offset += 6;
    int interfaceCount = readUnsignedShort(bytes, offset);
    offset += 2 + interfaceCount * 2;
    int fieldCount = readUnsignedShort(bytes, offset);
    offset += 2;
    for (int index = 0; index < fieldCount; index++) {
      offset = skipMember(bytes, offset);
    }

    int methodCount = readUnsignedShort(bytes, offset);
    offset += 2;
    List<MethodLayout> methods = new ArrayList<>(methodCount);
    for (int index = 0; index < methodCount; index++) {
      int nameIndex = readUnsignedShort(bytes, offset + 2);
      int attributeCountOffset = offset + 6;
      int attributeCount = readUnsignedShort(bytes, attributeCountOffset);
      int attributeOffset = attributeCountOffset + 2;
      CodeLayout code = null;
      for (int attributeIndex = 0; attributeIndex < attributeCount; attributeIndex++) {
        AttributeLayout attribute = attribute(bytes, utf8, attributeOffset);
        if (attribute.name().equals("Code")) {
          int codeLength = readInt(bytes, attribute.infoStart() + 4);
          code = new CodeLayout(
              attribute,
              attribute.infoStart() + 8,
              codeLength
          );
        }
        attributeOffset = attribute.end();
      }
      methods.add(new MethodLayout(
          utf8[nameIndex],
          attributeCountOffset,
          attributeOffset,
          code
      ));
      offset = attributeOffset;
    }

    int classAttributeCount = readUnsignedShort(bytes, offset);
    offset += 2;
    List<AttributeLayout> classAttributes = new ArrayList<>(classAttributeCount);
    for (int index = 0; index < classAttributeCount; index++) {
      AttributeLayout attribute = attribute(bytes, utf8, offset);
      classAttributes.add(attribute);
      offset = attribute.end();
    }
    require(offset == bytes.length, "fixture class layout has trailing or missing bytes");
    return new ClassLayout(utf8, utf8Layouts, methods, classAttributes);
  }

  private static int skipMember(byte[] bytes, int offset) {
    int attributeCount = readUnsignedShort(bytes, offset + 6);
    offset += 8;
    for (int index = 0; index < attributeCount; index++) {
      int length = readInt(bytes, offset + 2);
      offset += 6 + length;
    }
    return offset;
  }

  private static AttributeLayout attribute(
      byte[] bytes,
      String[] utf8,
      int offset
  ) {
    String name = utf8[readUnsignedShort(bytes, offset)];
    int lengthOffset = offset + 2;
    int length = readInt(bytes, lengthOffset);
    require(length >= 0, "fixture attribute length is negative");
    int infoStart = offset + 6;
    int end = infoStart + length;
    require(end <= bytes.length, "fixture attribute is truncated");
    return new AttributeLayout(name, offset, lengthOffset, length, infoStart, end);
  }

  private static int unsigned(byte value) {
    return value & 0xff;
  }

  private static int readUnsignedShort(byte[] values, int offset) {
    return unsigned(values[offset]) << 8 | unsigned(values[offset + 1]);
  }

  private static void writeUnsignedShort(byte[] values, int offset, int value) {
    values[offset] = (byte) (value >>> 8);
    values[offset + 1] = (byte) value;
  }

  private static int readInt(byte[] values, int offset) {
    return unsigned(values[offset]) << 24
        | unsigned(values[offset + 1]) << 16
        | unsigned(values[offset + 2]) << 8
        | unsigned(values[offset + 3]);
  }

  private static void writeInt(byte[] values, int offset, int value) {
    values[offset] = (byte) (value >>> 24);
    values[offset + 1] = (byte) (value >>> 16);
    values[offset + 2] = (byte) (value >>> 8);
    values[offset + 3] = (byte) value;
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
    }
  }

  private record ClassLayout(
      String[] utf8,
      Utf8Layout[] utf8Layouts,
      List<MethodLayout> methods,
      List<AttributeLayout> classAttributes
  ) {
    MethodLayout method(String name) {
      return methods.stream()
          .filter(method -> method.name().equals(name))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("missing fixture method " + name));
    }

    AttributeLayout classAttribute(String name) {
      return classAttributes.stream()
          .filter(attribute -> attribute.name().equals(name))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException(
              "missing fixture class attribute " + name
          ));
    }

    Utf8Layout utf8(String value) {
      for (int index = 1; index < utf8.length; index++) {
        if (value.equals(utf8[index])) {
          return utf8Layouts[index];
        }
      }
      throw new IllegalArgumentException("missing fixture UTF-8 entry " + value);
    }
  }

  private record MethodLayout(
      String name,
      int attributeCountOffset,
      int end,
      CodeLayout code
  ) {
    MethodLayout {
      if (code == null) {
        throw new IllegalArgumentException("fixture method has no Code attribute " + name);
      }
    }
  }

  private record CodeLayout(
      AttributeLayout attribute,
      int codeStart,
      int codeLength
  ) {
    int codeEnd() {
      return codeStart + codeLength;
    }
  }

  private record AttributeLayout(
      String name,
      int start,
      int lengthOffset,
      int length,
      int infoStart,
      int end
  ) {
  }

  private record Utf8Layout(int bytesStart, int length) {
  }
}
