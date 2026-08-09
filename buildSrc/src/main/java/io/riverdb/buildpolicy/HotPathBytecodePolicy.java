package io.riverdb.buildpolicy;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.ThrowInstruction;
import java.lang.constant.DirectMethodHandleDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Java 25 class-file checks for exact, explicitly designated hot methods. */
public final class HotPathBytecodePolicy {
  private static final String STRING_CONCAT_FACTORY =
      "Ljava/lang/invoke/StringConcatFactory;";
  private static final String LAMBDA_METAFACTORY =
      "Ljava/lang/invoke/LambdaMetafactory;";
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
   * Parses, verifies, and audits exact Java 25 class files. Rules are denied
   * unless an exact method/rule/detail occurrence is supplied as an allowance.
   */
  public static List<String> violations(
      Path root,
      Collection<Path> classFiles,
      Collection<MethodScope> scopes,
      Map<MethodScope, Set<Allowance>> allowances
  ) {
    List<String> violations = new ArrayList<>();
    Map<String, ParsedClass> classes = parseClasses(root, classFiles, violations);

    Set<MethodScope> selected = new LinkedHashSet<>(scopes);
    if (selected.size() != scopes.size()) {
      violations.add("duplicate hot-path method scope");
    }
    Set<MethodScope> unknownAllowlistScopes = new LinkedHashSet<>(allowances.keySet());
    unknownAllowlistScopes.removeAll(selected);
    if (!unknownAllowlistScopes.isEmpty()) {
      violations.add("allowlist entries are not selected hot methods: "
          + unknownAllowlistScopes);
    }

    for (MethodScope scope : selected) {
      ParsedClass parsed = classes.get(scope.className());
      if (parsed == null) {
        violations.add("missing hot-path class " + scope.displayName());
        continue;
      }
      MethodModel method = parsed.model().methods().stream()
          .filter(candidate -> candidate.methodName().equalsString(scope.methodName())
              && candidate.methodType().equalsString(scope.descriptor()))
          .findFirst()
          .orElse(null);
      if (method == null) {
        violations.add("missing hot-path method " + scope.displayName());
        continue;
      }
      CodeModel code = method.code().orElse(null);
      if (code == null) {
        violations.add("hot-path method has no code " + scope.displayName());
        continue;
      }
      auditMethod(
          parsed,
          method,
          code,
          classes,
          allowances.getOrDefault(scope, Set.of()),
          violations
      );
    }
    Collections.sort(violations);
    return List.copyOf(violations);
  }

  private static Map<String, ParsedClass> parseClasses(
      Path root,
      Collection<Path> classFiles,
      List<String> violations
  ) {
    ClassFile parser = ClassFile.of();
    Map<String, ParsedClass> classes = new LinkedHashMap<>();
    classFiles.stream().sorted().forEach(path -> {
      Path displayPath = relative(root, path);
      try {
        byte[] bytes = Files.readAllBytes(path);
        ClassModel model = parser.parse(bytes);
        if (model.majorVersion() != ClassFile.JAVA_25_VERSION
            || model.minorVersion() != 0) {
          violations.add(displayPath + ": expected Java 25 class version "
              + ClassFile.JAVA_25_VERSION + ".0, found "
              + model.majorVersion() + "." + model.minorVersion());
          return;
        }
        forceTraversal(model);
        String className = model.thisClass().asInternalName();
        ParsedClass parsed = new ParsedClass(displayPath, model);
        ParsedClass previous = classes.putIfAbsent(className, parsed);
        if (previous != null) {
          violations.add(displayPath + ": duplicate class input " + className);
        }
      } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException exception) {
        violations.add(displayPath + ": malformed class file: "
            + exception.getClass().getSimpleName() + ": " + stableMessage(exception));
      }
    });
    return classes;
  }

  private static void forceTraversal(ClassModel model) {
    model.fields().forEach(field -> field.forEach(element -> {
    }));
    model.methods().forEach(method -> {
      method.forEach(element -> {
      });
      method.code().ifPresent(code -> code.forEach(element -> {
        if (element instanceof Instruction instruction) {
          instruction.opcode();
          instruction.sizeInBytes();
        }
      }));
    });
    model.forEach(element -> {
    });
  }

  private static void auditMethod(
      ParsedClass owner,
      MethodModel method,
      CodeModel code,
      Map<String, ParsedClass> classes,
      Set<Allowance> allowed,
      List<String> violations
  ) {
    Set<Allowance> usedAllowances = new LinkedHashSet<>();
    int instructionIndex = 0;
    for (CodeElement element : code) {
      if (!(element instanceof Instruction instruction)) {
        continue;
      }
      if (instruction instanceof NewObjectInstruction allocation) {
        String allocatedClass = allocation.className().asInternalName();
        String detail = "new " + allocatedClass.replace('/', '.');
        addViolation(
            owner,
            method,
            instructionIndex,
            Rule.OBJECT_ALLOCATION,
            detail,
            allowed,
            usedAllowances,
            violations
        );
        ThrowableState throwable = throwableState(allocatedClass, classes);
        if (throwable == ThrowableState.YES) {
          addViolation(
              owner,
              method,
              instructionIndex,
              Rule.EXCEPTION_CONSTRUCTION,
              detail,
              allowed,
              usedAllowances,
              violations
          );
        } else if (throwable == ThrowableState.UNKNOWN) {
          addViolation(
              owner,
              method,
              instructionIndex,
              Rule.EXCEPTION_CONSTRUCTION,
              "unresolved throwable ancestry for " + detail,
              allowed,
              usedAllowances,
              violations
          );
        }
      } else if (instruction instanceof NewPrimitiveArrayInstruction allocation) {
        addViolation(
            owner,
            method,
            instructionIndex,
            Rule.ARRAY_ALLOCATION,
            "new " + allocation.typeKind().name().toLowerCase(java.util.Locale.ROOT)
                + " array",
            allowed,
            usedAllowances,
            violations
        );
      } else if (instruction instanceof NewReferenceArrayInstruction allocation) {
        addViolation(
            owner,
            method,
            instructionIndex,
            Rule.ARRAY_ALLOCATION,
            "new " + allocation.componentType().asInternalName().replace('/', '.') + " array",
            allowed,
            usedAllowances,
            violations
        );
      } else if (instruction instanceof NewMultiArrayInstruction allocation) {
        addViolation(
            owner,
            method,
            instructionIndex,
            Rule.ARRAY_ALLOCATION,
            "new " + allocation.arrayType().asInternalName().replace('/', '.')
                + " dimensions=" + allocation.dimensions(),
            allowed,
            usedAllowances,
            violations
        );
      } else if (instruction instanceof ThrowInstruction) {
        addViolation(
            owner,
            method,
            instructionIndex,
            Rule.EXCEPTION_THROW,
            "athrow",
            allowed,
            usedAllowances,
            violations
        );
      } else if (instruction instanceof InvokeInstruction invocation) {
        auditInvocation(
            owner,
            method,
            instructionIndex,
            invocation,
            allowed,
            usedAllowances,
            violations
        );
      } else if (instruction instanceof InvokeDynamicInstruction invocation) {
        auditInvokeDynamic(
            owner,
            method,
            instructionIndex,
            invocation,
            allowed,
            usedAllowances,
            violations
        );
      }
      instructionIndex++;
    }

    Set<Allowance> unusedAllowances = new LinkedHashSet<>(allowed);
    unusedAllowances.removeAll(usedAllowances);
    unusedAllowances.stream()
        .sorted((left, right) -> {
          int ruleOrder = left.rule().compareTo(right.rule());
          return ruleOrder == 0 ? left.detail().compareTo(right.detail()) : ruleOrder;
        })
        .forEach(allowance -> violations.add(
            owner.path() + ": stale hot-path allowlist " + methodDisplay(owner, method)
                + ": " + allowance.rule().diagnostic()
                + " (" + allowance.detail() + ")"
        ));
  }

  private static void auditInvocation(
      ParsedClass owner,
      MethodModel method,
      int instructionIndex,
      InvokeInstruction invocation,
      Set<Allowance> allowed,
      Set<Allowance> usedAllowances,
      List<String> violations
  ) {
    MethodReference reference = new MethodReference(
        invocation.owner().asInternalName(),
        invocation.name().stringValue(),
        invocation.type().stringValue()
    );
    if (isBoxing(reference)) {
      addViolation(
          owner,
          method,
          instructionIndex,
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
          instructionIndex,
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
          instructionIndex,
          Rule.STRING_FORMAT,
          reference.displayName(),
          allowed,
          usedAllowances,
          violations
      );
    }
  }

  private static void auditInvokeDynamic(
      ParsedClass owner,
      MethodModel method,
      int instructionIndex,
      InvokeDynamicInstruction invocation,
      Set<Allowance> allowed,
      Set<Allowance> usedAllowances,
      List<String> violations
  ) {
    DirectMethodHandleDesc bootstrap = invocation.bootstrapMethod();
    String bootstrapOwner = bootstrap.owner().descriptorString();
    Rule rule = bootstrapOwner.equals(STRING_CONCAT_FACTORY)
        ? Rule.STRING_CONCAT
        : Rule.OTHER_INVOKEDYNAMIC;
    String kind = bootstrapOwner.equals(LAMBDA_METAFACTORY)
        ? "lambda metafactory "
        : "bootstrap ";
    String detail = kind + bootstrap.owner().displayName() + "#"
        + bootstrap.methodName() + bootstrap.lookupDescriptor();
    addViolation(
        owner,
        method,
        instructionIndex,
        rule,
        detail,
        allowed,
        usedAllowances,
        violations
    );
  }

  private static void addViolation(
      ParsedClass owner,
      MethodModel method,
      int instructionIndex,
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
    violations.add(owner.path() + ": " + methodDisplay(owner, method)
        + " instruction " + instructionIndex + ": " + rule.diagnostic()
        + " (" + detail + ")");
  }

  private static String methodDisplay(ParsedClass owner, MethodModel method) {
    return owner.model().thisClass().asInternalName().replace('/', '.') + "#"
        + method.methodName().stringValue() + method.methodType().stringValue();
  }

  private static boolean isBoxing(MethodReference reference) {
    return WRAPPERS.contains(reference.owner())
        && (reference.name().equals("valueOf") || reference.name().equals("<init>"));
  }

  private static boolean isStream(MethodReference reference) {
    return reference.owner().startsWith("java/util/stream/")
        || returnDescriptor(reference.descriptor()).startsWith("Ljava/util/stream/");
  }

  private static String returnDescriptor(String methodDescriptor) {
    int separator = methodDescriptor.lastIndexOf(')');
    return separator < 0 ? "" : methodDescriptor.substring(separator + 1);
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

  private static ThrowableState throwableState(
      String className,
      Map<String, ParsedClass> classes
  ) {
    Set<String> visited = new HashSet<>();
    String current = className;
    while (current != null && visited.add(current)) {
      ParsedClass parsed = classes.get(current);
      if (parsed == null) {
        try {
          Class<?> type = Class.forName(
              current.replace('/', '.'),
              false,
              HotPathBytecodePolicy.class.getClassLoader()
          );
          return Throwable.class.isAssignableFrom(type)
              ? ThrowableState.YES
              : ThrowableState.NO;
        } catch (ClassNotFoundException | LinkageError exception) {
          return ThrowableState.UNKNOWN;
        }
      }
      current = parsed.model().superclass()
          .map(entry -> entry.asInternalName())
          .orElse(null);
    }
    return current == null ? ThrowableState.NO : ThrowableState.UNKNOWN;
  }

  private static String stableMessage(Throwable throwable) {
    String message = throwable.getMessage();
    return message == null || message.isBlank() ? "no detail" : message;
  }

  private static Path relative(Path root, Path path) {
    Path absoluteRoot = root.toAbsolutePath().normalize();
    Path absolutePath = path.toAbsolutePath().normalize();
    return absolutePath.startsWith(absoluteRoot)
        ? absoluteRoot.relativize(absolutePath)
        : path;
  }

  private record ParsedClass(Path path, ClassModel model) {
  }

  private record MethodReference(String owner, String name, String descriptor) {
    String displayName() {
      return owner.replace('/', '.') + "#" + name + descriptor;
    }
  }

  private enum ThrowableState {
    YES,
    NO,
    UNKNOWN
  }
}
