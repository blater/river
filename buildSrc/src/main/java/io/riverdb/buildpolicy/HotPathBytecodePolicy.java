package io.riverdb.buildpolicy;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Structured class-file checks for exact, explicitly designated hot methods. */
public final class HotPathBytecodePolicy {
  private static final int CLASS_MAGIC = 0xCAFEBABE;
  private static final int ACC_VARARGS = 0x0080;

  private static final int NEW = 187;
  private static final int NEWARRAY = 188;
  private static final int ANEWARRAY = 189;
  private static final int ATHROW = 191;
  private static final int MULTIANEWARRAY = 197;
  private static final int INVOKEVIRTUAL = 182;
  private static final int INVOKESPECIAL = 183;
  private static final int INVOKESTATIC = 184;
  private static final int INVOKEINTERFACE = 185;
  private static final int INVOKEDYNAMIC = 186;

  private static final Set<String> WRAPPERS = Set.of(
      "java/lang/Boolean",
      "java/lang/Byte",
      "java/lang/Short",
      "java/lang/Character",
      "java/lang/Integer",
      "java/lang/Long",
      "java/lang/Float",
      "java/lang/Double"
  );

  private HotPathBytecodePolicy() {
  }

  /** A method selected by binary class name, JVM method name, and descriptor. */
  public record MethodScope(String className, String methodName, String descriptor) {
    public MethodScope {
      if (className.isBlank() || methodName.isBlank() || descriptor.isBlank()) {
        throw new IllegalArgumentException("hot-path method scope fields must be non-blank");
      }
    }

    String displayName() {
      return className.replace('/', '.') + "#" + methodName + descriptor;
    }
  }

  /** One exact rule/detail occurrence permitted in one exact selected method. */
  public record Allowance(Rule rule, String detail) {
    public Allowance {
      if (rule == null || detail.isBlank()) {
        throw new IllegalArgumentException("hot-path allowance fields must be present");
      }
    }
  }

  /** Stable rule identifiers used by fixtures and narrowly reviewed allowlists. */
  public enum Rule {
    OBJECT_ALLOCATION("HP001", "object allocation"),
    ARRAY_ALLOCATION("HP002", "array allocation"),
    BOXING("HP003", "primitive wrapper boxing"),
    STREAM_API("HP004", "stream or collector call"),
    STRING_FORMAT("HP005", "string formatting call"),
    EXCEPTION_CONSTRUCTION("HP006", "exception construction"),
    EXCEPTION_THROW("HP007", "exception throw"),
    STRING_CONCAT("HP008", "invokedynamic string concatenation"),
    VARARGS_ARRAY("HP009", "object array passed to a known varargs method"),
    OTHER_INVOKEDYNAMIC("HP010", "non-concatenation invokedynamic call site");

    private final String identifier;
    private final String description;

    Rule(String identifier, String description) {
      this.identifier = identifier;
      this.description = description;
    }

    String diagnostic() {
      return identifier + " " + description;
    }
  }

  /**
   * Audits exact scopes in structured class files. Rules are denied unless the
   * exact method/rule pair appears in the supplied allowlist.
   */
  public static List<String> violations(
      Path root,
      Collection<Path> classFiles,
      Collection<MethodScope> scopes,
      Map<MethodScope, Set<Allowance>> allowances
  ) {
    Map<String, ParsedClass> classes = new LinkedHashMap<>();
    classFiles.stream().sorted().forEach(path -> {
      ParsedClass parsed = parse(root, path);
      ParsedClass previous = classes.putIfAbsent(parsed.name(), parsed);
      if (previous != null) {
        throw new IllegalArgumentException("duplicate class input " + parsed.name());
      }
    });

    Set<MethodScope> selected = new LinkedHashSet<>(scopes);
    if (selected.size() != scopes.size()) {
      throw new IllegalArgumentException("duplicate hot-path method scope");
    }
    Set<MethodScope> unknownAllowlistScopes = new LinkedHashSet<>(allowances.keySet());
    unknownAllowlistScopes.removeAll(selected);
    if (!unknownAllowlistScopes.isEmpty()) {
      throw new IllegalArgumentException(
          "allowlist entries are not selected hot methods: " + unknownAllowlistScopes
      );
    }

    Map<MethodKey, Integer> methodAccess = new HashMap<>();
    classes.values().forEach(parsed -> parsed.methods().forEach(method ->
        methodAccess.put(
            new MethodKey(parsed.name(), method.name(), method.descriptor()),
            method.access()
        )
    ));

    List<String> violations = new ArrayList<>();
    for (MethodScope scope : selected) {
      ParsedClass parsed = classes.get(scope.className());
      if (parsed == null) {
        violations.add("missing hot-path class " + scope.displayName());
        continue;
      }
      ParsedMethod method = parsed.methods().stream()
          .filter(candidate -> candidate.name().equals(scope.methodName())
              && candidate.descriptor().equals(scope.descriptor()))
          .findFirst()
          .orElse(null);
      if (method == null) {
        violations.add("missing hot-path method " + scope.displayName());
        continue;
      }
      if (method.code() == null) {
        violations.add("hot-path method has no code " + scope.displayName());
        continue;
      }
      Set<Allowance> allowed = allowances.getOrDefault(scope, Set.of());
      auditMethod(parsed, method, classes, methodAccess, allowed, violations);
    }
    Collections.sort(violations);
    return List.copyOf(violations);
  }

  private static void auditMethod(
      ParsedClass owner,
      ParsedMethod method,
      Map<String, ParsedClass> classes,
      Map<MethodKey, Integer> methodAccess,
      Set<Allowance> allowed,
      List<String> violations
  ) {
    List<Instruction> instructions = instructions(method.code());
    Set<Allowance> usedAllowances = new LinkedHashSet<>();
    for (int index = 0; index < instructions.size(); index++) {
      Instruction instruction = instructions.get(index);
      int opcode = instruction.opcode();
      if (opcode == NEW) {
        String allocatedClass = owner.pool().className(instruction.constantPoolIndex());
        addViolation(
            owner,
            method,
            instruction,
            Rule.OBJECT_ALLOCATION,
            "new " + allocatedClass.replace('/', '.'),
            allowed,
            usedAllowances,
            violations
        );
        if (isThrowable(allocatedClass, classes)) {
          addViolation(
              owner,
              method,
              instruction,
              Rule.EXCEPTION_CONSTRUCTION,
              "new " + allocatedClass.replace('/', '.'),
              allowed,
              usedAllowances,
              violations
          );
        }
      } else if (opcode == NEWARRAY || opcode == ANEWARRAY || opcode == MULTIANEWARRAY) {
        String detail = opcode == NEWARRAY
            ? "new primitive array"
            : "new " + owner.pool().className(instruction.constantPoolIndex()).replace('/', '.')
                + " array";
        addViolation(
            owner,
            method,
            instruction,
            Rule.ARRAY_ALLOCATION,
            detail,
            allowed,
            usedAllowances,
            violations
        );
        if (opcode == ANEWARRAY
            && owner.pool().className(instruction.constantPoolIndex()).equals("java/lang/Object")
            && feedsKnownVarargs(instructions, index, owner.pool(), methodAccess)) {
          addViolation(
              owner,
              method,
              instruction,
              Rule.VARARGS_ARRAY,
              "new java.lang.Object[]",
              allowed,
              usedAllowances,
              violations
          );
        }
      } else if (opcode == ATHROW) {
        addViolation(
            owner,
            method,
            instruction,
            Rule.EXCEPTION_THROW,
            "athrow",
            allowed,
            usedAllowances,
            violations
        );
      } else if (isInvocation(opcode)) {
        MethodReference reference = owner.pool().methodReference(
            instruction.constantPoolIndex()
        );
        if (isBoxing(reference)) {
          addViolation(
              owner,
              method,
              instruction,
              Rule.BOXING,
              reference.displayName(),
              allowed,
              usedAllowances,
              violations
          );
        }
        if (isStream(reference)) {
          addViolation(
              owner,
              method,
              instruction,
              Rule.STREAM_API,
              reference.displayName(),
              allowed,
              usedAllowances,
              violations
          );
        }
        if (isFormatting(reference)) {
          addViolation(
              owner,
              method,
              instruction,
              Rule.STRING_FORMAT,
              reference.displayName(),
              allowed,
              usedAllowances,
              violations
          );
        }
      } else if (opcode == INVOKEDYNAMIC) {
        NameAndType callSite = owner.pool().invokeDynamicNameAndType(
            instruction.constantPoolIndex()
        );
        Rule rule = callSite.name().startsWith("makeConcat")
            ? Rule.STRING_CONCAT
            : Rule.OTHER_INVOKEDYNAMIC;
        addViolation(
            owner,
            method,
            instruction,
            rule,
            callSite.name() + callSite.descriptor(),
            allowed,
            usedAllowances,
            violations
        );
      }
    }
    Set<Allowance> unusedAllowances = new LinkedHashSet<>(allowed);
    unusedAllowances.removeAll(usedAllowances);
    unusedAllowances.stream()
        .sorted((left, right) -> {
          int ruleOrder = left.rule().compareTo(right.rule());
          return ruleOrder == 0 ? left.detail().compareTo(right.detail()) : ruleOrder;
        })
        .forEach(allowance -> violations.add(
        owner.path() + ": stale hot-path allowlist " + owner.name().replace('/', '.') + "#"
            + method.name() + method.descriptor() + ": " + allowance.rule().diagnostic()
            + " (" + allowance.detail() + ")"
        ));
  }

  private static void addViolation(
      ParsedClass owner,
      ParsedMethod method,
      Instruction instruction,
      Rule rule,
      String detail,
      Set<Allowance> allowed,
      Set<Allowance> usedAllowances,
      List<String> violations
  ) {
    Allowance allowance = new Allowance(rule, detail);
    if (allowed.contains(allowance) && usedAllowances.add(allowance)) {
      return;
    }
    violations.add(owner.path() + ": " + owner.name().replace('/', '.') + "#"
        + method.name() + method.descriptor() + " @" + instruction.offset() + ": "
        + rule.diagnostic() + " (" + detail + ")");
  }

  private static boolean isInvocation(int opcode) {
    return opcode == INVOKEVIRTUAL
        || opcode == INVOKESPECIAL
        || opcode == INVOKESTATIC
        || opcode == INVOKEINTERFACE;
  }

  private static boolean isBoxing(MethodReference reference) {
    return WRAPPERS.contains(reference.owner())
        && (reference.name().equals("valueOf") || reference.name().equals("<init>"));
  }

  private static boolean isStream(MethodReference reference) {
    return reference.owner().startsWith("java/util/stream/")
        || reference.owner().equals("java/util/stream/Collectors")
        || reference.name().equals("stream")
        || reference.name().equals("parallelStream");
  }

  private static boolean isFormatting(MethodReference reference) {
    if (reference.owner().equals("java/util/Formatter")) {
      return true;
    }
    return (reference.owner().equals("java/lang/String")
        || reference.owner().equals("java/io/PrintStream")
        || reference.owner().equals("java/io/PrintWriter"))
        && (reference.name().equals("format")
            || reference.name().equals("formatted")
            || reference.name().equals("printf"));
  }

  private static boolean feedsKnownVarargs(
      List<Instruction> instructions,
      int allocationIndex,
      ConstantPool pool,
      Map<MethodKey, Integer> methodAccess
  ) {
    int limit = Math.min(instructions.size(), allocationIndex + 24);
    for (int index = allocationIndex + 1; index < limit; index++) {
      Instruction candidate = instructions.get(index);
      if (isControlTransfer(candidate.opcode())) {
        return false;
      }
      if (!isInvocation(candidate.opcode())) {
        continue;
      }
      MethodReference reference = pool.methodReference(candidate.constantPoolIndex());
      MethodKey key = new MethodKey(
          reference.owner(),
          reference.name(),
          reference.descriptor()
      );
      int access = methodAccess.getOrDefault(key, 0);
      if ((access & ACC_VARARGS) != 0
          && reference.descriptor().contains("[Ljava/lang/Object;")) {
        return true;
      }
    }
    return false;
  }

  private static boolean isControlTransfer(int opcode) {
    return (opcode >= 153 && opcode <= 171)
        || (opcode >= 172 && opcode <= 177)
        || opcode == ATHROW
        || opcode == 198
        || opcode == 199
        || opcode == 200
        || opcode == 201;
  }

  private static boolean isThrowable(String className, Map<String, ParsedClass> classes) {
    Set<String> visited = new HashSet<>();
    String current = className;
    while (current != null && visited.add(current)) {
      if (current.equals("java/lang/Throwable")
          || current.endsWith("Exception")
          || current.endsWith("Error")) {
        return true;
      }
      ParsedClass parsed = classes.get(current);
      current = parsed == null ? null : parsed.superName();
    }
    return false;
  }

  private static ParsedClass parse(Path root, Path path) {
    try (DataInputStream input = new DataInputStream(
        new ByteArrayInputStream(Files.readAllBytes(path)))) {
      if (input.readInt() != CLASS_MAGIC) {
        throw new IllegalArgumentException("not a class file: " + path);
      }
      input.readUnsignedShort();
      input.readUnsignedShort();
      ConstantPool pool = ConstantPool.read(input);
      input.readUnsignedShort();
      String className = pool.className(input.readUnsignedShort());
      int superIndex = input.readUnsignedShort();
      String superName = superIndex == 0 ? null : pool.className(superIndex);
      skipInterfaces(input);
      skipMembers(input, pool);
      List<ParsedMethod> methods = readMethods(input, pool);
      skipAttributes(input, pool);
      if (input.available() != 0) {
        throw new IllegalArgumentException("trailing class-file bytes: " + path);
      }
      return new ParsedClass(relative(root, path), className, superName, pool, methods);
    } catch (IOException exception) {
      throw new IllegalArgumentException("cannot parse class file " + path, exception);
    }
  }

  private static Path relative(Path root, Path path) {
    Path absoluteRoot = root.toAbsolutePath().normalize();
    Path absolutePath = path.toAbsolutePath().normalize();
    return absolutePath.startsWith(absoluteRoot)
        ? absoluteRoot.relativize(absolutePath)
        : path;
  }

  private static void skipInterfaces(DataInputStream input) throws IOException {
    int count = input.readUnsignedShort();
    input.skipNBytes((long) count * 2);
  }

  private static void skipMembers(DataInputStream input, ConstantPool pool) throws IOException {
    int count = input.readUnsignedShort();
    for (int index = 0; index < count; index++) {
      input.readUnsignedShort();
      input.readUnsignedShort();
      input.readUnsignedShort();
      skipAttributes(input, pool);
    }
  }

  private static List<ParsedMethod> readMethods(
      DataInputStream input,
      ConstantPool pool
  ) throws IOException {
    int count = input.readUnsignedShort();
    List<ParsedMethod> methods = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      int access = input.readUnsignedShort();
      String name = pool.utf8(input.readUnsignedShort());
      String descriptor = pool.utf8(input.readUnsignedShort());
      int attributeCount = input.readUnsignedShort();
      byte[] code = null;
      for (int attributeIndex = 0; attributeIndex < attributeCount; attributeIndex++) {
        String attributeName = pool.utf8(input.readUnsignedShort());
        int length = input.readInt();
        if (attributeName.equals("Code")) {
          input.readUnsignedShort();
          input.readUnsignedShort();
          int codeLength = input.readInt();
          code = input.readNBytes(codeLength);
          if (code.length != codeLength) {
            throw new IOException("truncated Code attribute");
          }
          int exceptionCount = input.readUnsignedShort();
          input.skipNBytes((long) exceptionCount * 8);
          skipAttributes(input, pool);
        } else {
          input.skipNBytes(Integer.toUnsignedLong(length));
        }
      }
      methods.add(new ParsedMethod(access, name, descriptor, code));
    }
    return List.copyOf(methods);
  }

  private static void skipAttributes(
      DataInputStream input,
      ConstantPool pool
  ) throws IOException {
    int count = input.readUnsignedShort();
    for (int index = 0; index < count; index++) {
      pool.utf8(input.readUnsignedShort());
      int length = input.readInt();
      input.skipNBytes(Integer.toUnsignedLong(length));
    }
  }

  private static List<Instruction> instructions(byte[] code) {
    List<Instruction> instructions = new ArrayList<>();
    int offset = 0;
    while (offset < code.length) {
      int opcode = unsigned(code[offset]);
      int constantPoolIndex = -1;
      if (opcode == NEW
          || opcode == ANEWARRAY
          || opcode == MULTIANEWARRAY
          || isInvocation(opcode)
          || opcode == INVOKEDYNAMIC) {
        constantPoolIndex = unsignedShort(code, offset + 1);
      }
      instructions.add(new Instruction(offset, opcode, constantPoolIndex));
      int length = instructionLength(code, offset, opcode);
      if (length <= 0 || offset + length > code.length) {
        throw new IllegalArgumentException("malformed bytecode at offset " + offset);
      }
      offset += length;
    }
    return List.copyOf(instructions);
  }

  private static int instructionLength(byte[] code, int offset, int opcode) {
    if (opcode == 170 || opcode == 171) {
      int padding = (4 - ((offset + 1) & 3)) & 3;
      int header = offset + 1 + padding;
      if (opcode == 170) {
        int low = signedInt(code, header + 4);
        int high = signedInt(code, header + 8);
        long entries = (long) high - low + 1;
        if (entries < 0 || entries > Integer.MAX_VALUE / 4) {
          throw new IllegalArgumentException("malformed tableswitch at " + offset);
        }
        return 1 + padding + 12 + (int) entries * 4;
      }
      int pairs = signedInt(code, header + 4);
      if (pairs < 0 || pairs > Integer.MAX_VALUE / 8) {
        throw new IllegalArgumentException("malformed lookupswitch at " + offset);
      }
      return 1 + padding + 8 + pairs * 8;
    }
    if (opcode == 196) {
      int nested = unsigned(code[offset + 1]);
      return nested == 132 ? 6 : 4;
    }
    return switch (opcode) {
      case 16, 18,
          21, 22, 23, 24, 25,
          54, 55, 56, 57, 58,
          169, 188 -> 2;
      case 17, 19, 20, 132,
          153, 154, 155, 156, 157, 158,
          159, 160, 161, 162, 163, 164,
          165, 166, 167, 168,
          178, 179, 180, 181,
          182, 183, 184,
          187, 189, 192, 193,
          198, 199 -> 3;
      case 197 -> 4;
      case 185, 186, 200, 201 -> 5;
      default -> 1;
    };
  }

  private static int unsigned(byte value) {
    return value & 0xff;
  }

  private static int unsignedShort(byte[] values, int offset) {
    return unsigned(values[offset]) << 8 | unsigned(values[offset + 1]);
  }

  private static int signedInt(byte[] values, int offset) {
    return unsigned(values[offset]) << 24
        | unsigned(values[offset + 1]) << 16
        | unsigned(values[offset + 2]) << 8
        | unsigned(values[offset + 3]);
  }

  private record ParsedClass(
      Path path,
      String name,
      String superName,
      ConstantPool pool,
      List<ParsedMethod> methods
  ) {
  }

  private record ParsedMethod(int access, String name, String descriptor, byte[] code) {
  }

  private record Instruction(int offset, int opcode, int constantPoolIndex) {
  }

  private record MethodKey(String owner, String name, String descriptor) {
  }

  private record MethodReference(String owner, String name, String descriptor) {
    String displayName() {
      return owner.replace('/', '.') + "#" + name + descriptor;
    }
  }

  private record NameAndType(String name, String descriptor) {
  }

  private static final class ConstantPool {
    private final Object[] entries;

    private ConstantPool(Object[] entries) {
      this.entries = entries;
    }

    static ConstantPool read(DataInputStream input) throws IOException {
      int count = input.readUnsignedShort();
      Object[] entries = new Object[count];
      for (int index = 1; index < count; index++) {
        int tag = input.readUnsignedByte();
        entries[index] = switch (tag) {
          case 1 -> input.readUTF();
          case 3, 4 -> {
            input.readInt();
            yield null;
          }
          case 5, 6 -> {
            input.readLong();
            index++;
            yield null;
          }
          case 7 -> new ClassEntry(input.readUnsignedShort());
          case 8, 16, 19, 20 -> {
            input.readUnsignedShort();
            yield null;
          }
          case 9, 10, 11 -> new ReferenceEntry(
              input.readUnsignedShort(),
              input.readUnsignedShort()
          );
          case 12 -> new NameAndTypeEntry(
              input.readUnsignedShort(),
              input.readUnsignedShort()
          );
          case 15 -> {
            input.readUnsignedByte();
            input.readUnsignedShort();
            yield null;
          }
          case 17, 18 -> new DynamicEntry(
              input.readUnsignedShort(),
              input.readUnsignedShort()
          );
          default -> throw new IllegalArgumentException("unsupported constant-pool tag " + tag);
        };
      }
      return new ConstantPool(entries);
    }

    String utf8(int index) {
      Object entry = entry(index);
      if (!(entry instanceof String value)) {
        throw new IllegalArgumentException("constant-pool entry " + index + " is not UTF-8");
      }
      return value;
    }

    String className(int index) {
      Object entry = entry(index);
      if (!(entry instanceof ClassEntry value)) {
        throw new IllegalArgumentException("constant-pool entry " + index + " is not a class");
      }
      return utf8(value.nameIndex());
    }

    MethodReference methodReference(int index) {
      Object entry = entry(index);
      if (!(entry instanceof ReferenceEntry value)) {
        throw new IllegalArgumentException(
            "constant-pool entry " + index + " is not a member reference"
        );
      }
      NameAndType nameAndType = nameAndType(value.nameAndTypeIndex());
      return new MethodReference(
          className(value.classIndex()),
          nameAndType.name(),
          nameAndType.descriptor()
      );
    }

    NameAndType invokeDynamicNameAndType(int index) {
      Object entry = entry(index);
      if (!(entry instanceof DynamicEntry value)) {
        throw new IllegalArgumentException(
            "constant-pool entry " + index + " is not invokedynamic"
        );
      }
      return nameAndType(value.nameAndTypeIndex());
    }

    private NameAndType nameAndType(int index) {
      Object entry = entry(index);
      if (!(entry instanceof NameAndTypeEntry value)) {
        throw new IllegalArgumentException(
            "constant-pool entry " + index + " is not name-and-type"
        );
      }
      return new NameAndType(utf8(value.nameIndex()), utf8(value.descriptorIndex()));
    }

    private Object entry(int index) {
      if (index <= 0 || index >= entries.length) {
        throw new IllegalArgumentException("invalid constant-pool index " + index);
      }
      return entries[index];
    }
  }

  private record ClassEntry(int nameIndex) {
  }

  private record ReferenceEntry(int classIndex, int nameAndTypeIndex) {
  }

  private record NameAndTypeEntry(int nameIndex, int descriptorIndex) {
  }

  private record DynamicEntry(int bootstrapMethodIndex, int nameAndTypeIndex) {
  }
}
