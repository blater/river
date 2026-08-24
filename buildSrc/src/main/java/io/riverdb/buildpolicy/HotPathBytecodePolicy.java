package io.riverdb.buildpolicy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.VerifyError;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeElement;
import java.lang.classfile.Instruction;
import java.lang.classfile.instruction.InvokeDynamicInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LookupSwitchInstruction;
import java.lang.classfile.instruction.NewMultiArrayInstruction;
import java.lang.classfile.instruction.NewObjectInstruction;
import java.lang.classfile.instruction.NewPrimitiveArrayInstruction;
import java.lang.classfile.instruction.NewReferenceArrayInstruction;
import java.lang.classfile.instruction.TableSwitchInstruction;
import java.lang.constant.ClassDesc;
import java.lang.reflect.AccessFlag;
import java.lang.classfile.MethodModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Java 25 class-file checks for exact, explicitly designated hot methods. */
public final class HotPathBytecodePolicy {
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

  public static List<String> violations(
      Path root,
      Collection<Path> classFiles,
      Collection<MethodScope> scopes,
      Map<MethodScope, Set<Allowance>> allowances) {
    return violations(root, classFiles, List.of(), scopes, allowances);
  }

  /** Audits with an explicit directory/JAR hierarchy class path. */
  public static List<String> violations(
      Path root,
      Collection<Path> classFiles,
      Collection<Path> hierarchyEntries,
      Collection<MethodScope> scopes,
      Map<MethodScope, Set<Allowance>> allowances) {
    List<String> violations = new ArrayList<>();
    Map<String, ParsedClass> classes = parseClasses(root, classFiles, violations);
    ControlledHierarchy hierarchy = new ControlledHierarchy(classes, hierarchyEntries);
    Set<MethodScope> selected = new LinkedHashSet<>(scopes);
    if (selected.size() != scopes.size()) violations.add("duplicate hot-path method scope");
    Set<MethodScope> unknown = new LinkedHashSet<>(allowances.keySet());
    unknown.removeAll(selected);
    if (!unknown.isEmpty()) {
      violations.add("allowlist entries are not selected hot methods: " + unknown);
    }
    Set<String> targets = new LinkedHashSet<>();
    if (selected.isEmpty()) targets.addAll(classes.keySet());
    else selected.forEach(scope -> targets.add(scope.className()));
    verifyClasses(classes, hierarchy, targets, violations);
    for (MethodScope scope : selected) {
      ParsedClass parsed = classes.get(scope.className());
      if (parsed == null) {
        violations.add("missing hot-path class " + scope.displayName());
        continue;
      }
      MethodModel method = parsed.model().methods().stream()
          .filter(candidate -> candidate.methodName().equalsString(scope.methodName())
              && candidate.methodType().equalsString(scope.descriptor()))
          .findFirst().orElse(null);
      if (method == null) {
        violations.add("missing hot-path method " + scope.displayName());
        continue;
      }
      method.code().ifPresentOrElse(
          code -> HotPathBytecodeAuditor.audit(parsed, method, code, hierarchy,
              allowances.getOrDefault(scope, Set.of()), violations),
          () -> violations.add("hot-path method has no code " + scope.displayName()));
    }
    Collections.sort(violations);
    return List.copyOf(violations);
  }

  private static Map<String, ParsedClass> parseClasses(
      Path root, Collection<Path> classFiles, List<String> violations) {
    ClassFile parser = ClassFile.of();
    Map<String, ParsedClass> classes = new LinkedHashMap<>();
    classFiles.stream().sorted().forEach(path -> {
      Path displayPath = relative(root, path);
      try {
        byte[] bytes = Files.readAllBytes(path);
        ClassModel model = parser.parse(bytes);
        if (model.majorVersion() != ClassFile.JAVA_25_VERSION || model.minorVersion() != 0) {
          violations.add(displayPath + ": expected Java 25 class version "
              + ClassFile.JAVA_25_VERSION + ".0, found "
              + model.majorVersion() + "." + model.minorVersion());
          return;
        }
        forceTraversal(model);
        String className = model.thisClass().asInternalName();
        ParsedClass previous = classes.putIfAbsent(className,
            new ParsedClass(displayPath, model, bytes));
        if (previous != null) violations.add(displayPath + ": duplicate class input " + className);
      } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException exception) {
        violations.add(displayPath + ": malformed class file: "
            + exception.getClass().getSimpleName() + ": " + stableMessage(exception));
      }
    });
    return classes;
  }

  private static void verifyClasses(
      Map<String, ParsedClass> classes,
      ControlledHierarchy hierarchy,
      Set<String> targets,
      List<String> violations) {
    Set<ClassDesc> interfaces = new LinkedHashSet<>();
    Map<ClassDesc, ClassDesc> superclasses = new LinkedHashMap<>();
    classes.values().forEach(parsed -> {
      ClassModel model = parsed.model();
      ClassDesc type = model.thisClass().asSymbol();
      if (model.flags().has(AccessFlag.INTERFACE)) interfaces.add(type);
      else model.superclass().ifPresent(superclass -> superclasses.put(type, superclass.asSymbol()));
    });
    ClassHierarchyResolver resolver = ClassHierarchyResolver.of(interfaces, superclasses)
        .orElse(ClassHierarchyResolver.ofResourceParsing(hierarchy::openResource)).cached();
    ClassFile verifier = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
    targets.stream().map(classes::get).filter(java.util.Objects::nonNull).forEach(parsed -> {
      List<VerifyError> errors = verifier.verify(parsed.bytes());
      errors.forEach(error -> violations.add(parsed.path() + ": invalid class file: "
          + stableMessage(error)));
    });
  }

  private static void forceTraversal(ClassModel model) {
    model.fields().forEach(field -> field.forEach(element -> { }));
    model.methods().forEach(method -> {
      method.forEach(element -> { });
      method.code().ifPresent(code -> code.forEach(element -> {
        if (element instanceof Instruction instruction) forceInstruction(instruction);
      }));
    });
    model.forEach(element -> { });
  }

  private static void forceInstruction(Instruction instruction) {
    instruction.opcode();
    instruction.sizeInBytes();
    if (instruction instanceof InvokeInstruction invocation) {
      invocation.method(); invocation.owner(); invocation.name(); invocation.type();
      invocation.count(); invocation.isInterface();
    } else if (instruction instanceof InvokeDynamicInstruction invocation) {
      invocation.invokedynamic(); invocation.bootstrapMethod(); invocation.bootstrapArgs();
      invocation.name(); invocation.type();
    } else if (instruction instanceof NewObjectInstruction allocation) {
      allocation.className();
    } else if (instruction instanceof NewPrimitiveArrayInstruction allocation) {
      allocation.typeKind();
    } else if (instruction instanceof NewReferenceArrayInstruction allocation) {
      allocation.componentType();
    } else if (instruction instanceof NewMultiArrayInstruction allocation) {
      allocation.arrayType(); allocation.dimensions();
    } else if (instruction instanceof TableSwitchInstruction tableSwitch) {
      tableSwitch.lowValue(); tableSwitch.highValue(); tableSwitch.defaultTarget(); tableSwitch.cases();
    } else if (instruction instanceof LookupSwitchInstruction lookupSwitch) {
      lookupSwitch.defaultTarget(); lookupSwitch.cases();
    }
  }

  private static String stableMessage(Throwable throwable) {
    String message = throwable.getMessage();
    return message == null || message.isBlank() ? "no detail" : message;
  }

  private static Path relative(Path root, Path path) {
    Path absoluteRoot = root.toAbsolutePath().normalize();
    Path absolutePath = path.toAbsolutePath().normalize();
    return absolutePath.startsWith(absoluteRoot) ? absoluteRoot.relativize(absolutePath) : path;
  }

  record ParsedClass(Path path, ClassModel model, byte[] bytes) { }

  static final class ControlledHierarchy {
    private final Map<String, ParsedClass> localClasses;
    private final List<Path> entries;
    private final Map<String, Optional<ClassModel>> externalClasses = new LinkedHashMap<>();

    ControlledHierarchy(Map<String, ParsedClass> localClasses, Collection<Path> entries) {
      this.localClasses = localClasses;
      this.entries = entries.stream().map(path -> path.toAbsolutePath().normalize())
          .distinct().sorted().toList();
    }

    InputStream openResource(ClassDesc type) {
      String descriptor = type.descriptorString();
      if (!descriptor.startsWith("L") || !descriptor.endsWith(";")) return null;
      return openResource(descriptor.substring(1, descriptor.length() - 1) + ".class");
    }

    private InputStream openResource(String resourceName) {
      for (Path entry : entries) {
        try {
          if (Files.isDirectory(entry)) {
            Path candidate = entry.resolve(resourceName);
            if (Files.isRegularFile(candidate)) return new ByteArrayInputStream(Files.readAllBytes(candidate));
          } else if (Files.isRegularFile(entry)) {
            try (ZipFile archive = new ZipFile(entry.toFile())) {
              ZipEntry candidate = archive.getEntry(resourceName);
              if (candidate != null) {
                try (InputStream input = archive.getInputStream(candidate)) {
                  return new ByteArrayInputStream(input.readAllBytes());
                }
              }
            }
          }
        } catch (IOException exception) {
          throw new IllegalArgumentException("cannot read hierarchy entry " + entry
              + " for " + resourceName, exception);
        }
      }
      InputStream platform = ClassLoader.getPlatformClassLoader().getResourceAsStream(resourceName);
      if (platform == null) return null;
      try (platform) {
        return new ByteArrayInputStream(platform.readAllBytes());
      } catch (IOException exception) {
        throw new IllegalArgumentException("cannot read platform hierarchy resource "
            + resourceName, exception);
      }
    }

    Optional<String> superclass(String className) {
      ParsedClass local = localClasses.get(className);
      if (local != null) return local.model().superclass().map(entry -> entry.asInternalName());
      Optional<ClassModel> external = externalClasses.computeIfAbsent(className, this::loadExternal);
      if (external.isEmpty()) return null;
      return external.get().superclass().map(entry -> entry.asInternalName());
    }

    private Optional<ClassModel> loadExternal(String className) {
      try (InputStream input = openResource(className + ".class")) {
        if (input == null) return Optional.empty();
        return Optional.of(ClassFile.of().parse(input.readAllBytes()));
      } catch (IOException | IllegalArgumentException exception) {
        throw new IllegalArgumentException("cannot parse hierarchy class " + className, exception);
      }
    }
  }
}
