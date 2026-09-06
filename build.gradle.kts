import io.riverdb.buildpolicy.BuildPolicy
import io.riverdb.buildpolicy.ClassReferencePolicy
import io.riverdb.buildpolicy.HotPathBytecodeFixtureMutator
import io.riverdb.buildpolicy.HotPathBytecodePolicy
import io.riverdb.buildpolicy.LegacyEvidencePolicy
import io.riverdb.buildpolicy.ProvenancePolicy
import io.riverdb.buildpolicy.InvocationPolicy
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import java.nio.file.Files
import java.security.MessageDigest

plugins {
  base
}

group = "io.riverdb"
version = "0.1.0-alpha.2"

val productionModules = listOf(
  "river-base",
  "river-observability-api",
  "river-platform",
  "river-format",
  "river-tx-api",
  "river-wal",
  "river-buffer",
  "river-storage",
  "river-tx",
  "river-recovery",
  "river-backup",
  "river-catalog",
  "river-sql",
  "river-planner",
  "river-exec",
  "river-engine-api",
  "river-engine",
  "river-protocol",
  "river-client",
  "river-server",
  "river-jdbc",
  "river-cli",
  "river-admin",
  "river-inspect",
  "river-migration",
  "river-observability"
)

val allowedDependencies = mapOf(
  "river-base" to emptySet(),
  "river-observability-api" to emptySet(),
  "river-platform" to setOf("river-observability-api"),
  "river-format" to emptySet(),
  "river-tx-api" to emptySet(),
  "river-wal" to setOf(
    "river-platform", "river-format",
    "river-observability-api"
  ),
  "river-buffer" to setOf(
    "river-platform", "river-format",
    "river-observability-api"
  ),
  "river-storage" to setOf(
    "river-format", "river-buffer", "river-tx-api",
    "river-observability-api"
  ),
  "river-tx" to setOf(
    "river-tx-api", "river-observability-api"
  ),
  "river-recovery" to setOf(
    "river-wal", "river-buffer", "river-storage",
    "river-tx", "river-tx-api"
  ),
  "river-backup" to setOf(
    "river-platform", "river-format", "river-wal",
    "river-buffer", "river-storage", "river-recovery"
  ),
  "river-catalog" to setOf(
    "river-storage", "river-tx-api", "river-observability-api"
  ),
  "river-sql" to setOf("river-catalog"),
  "river-planner" to setOf("river-sql", "river-catalog", "river-storage"),
  "river-exec" to setOf(
    "river-planner", "river-storage", "river-tx-api", "river-catalog",
    "river-observability-api"
  ),
  "river-engine-api" to setOf("river-base"),
  "river-engine" to setOf(
    "river-platform", "river-format", "river-wal",
    "river-buffer", "river-storage", "river-tx-api", "river-tx",
    "river-recovery", "river-backup", "river-catalog", "river-sql",
    "river-planner", "river-exec", "river-engine-api"
  ),
  "river-protocol" to setOf("river-engine-api"),
  "river-client" to setOf("river-protocol", "river-engine-api"),
  "river-server" to setOf(
    "river-platform", "river-protocol", "river-engine-api", "river-engine"
  ),
  "river-jdbc" to setOf("river-client"),
  "river-cli" to setOf("river-client"),
  "river-admin" to setOf("river-client", "river-engine-api", "river-backup"),
  "river-inspect" to setOf("river-platform", "river-format", "river-wal"),
  "river-migration" to setOf("river-client"),
  "river-observability" to setOf("river-observability-api"),
  "river-bench" to productionModules.toSet()
).mapValues { (module, dependencies) ->
  if (module == "river-base" || module == "river-observability-api") {
    dependencies
  } else {
    dependencies + "river-base"
  }
}

val declaredDependencies = mapOf(
  "river-platform" to setOf("river-base"),
  "river-format" to setOf("river-base"),
  "river-tx-api" to setOf("river-base"),
  "river-wal" to setOf("river-base", "river-format", "river-platform"),
  "river-storage" to setOf("river-base", "river-format"),
  "river-tx" to setOf("river-base", "river-tx-api"),
  "river-backup" to setOf("river-base", "river-format", "river-platform"),
  "river-sql" to setOf("river-base"),
  "river-engine-api" to setOf("river-base"),
  "river-protocol" to setOf("river-base", "river-engine-api"),
  "river-client" to setOf("river-base", "river-engine-api", "river-protocol"),
  "river-server" to setOf(
    "river-base", "river-platform", "river-engine-api", "river-protocol"
  ),
  "river-jdbc" to setOf("river-base", "river-client"),
  "river-cli" to setOf("river-base", "river-client"),
  "river-engine" to setOf(
    "river-base", "river-format", "river-platform", "river-storage",
    "river-tx", "river-tx-api", "river-wal", "river-sql", "river-engine-api"
  ),
  "river-inspect" to setOf("river-base", "river-format", "river-platform"),
  "river-bench" to setOf(
    "river-base", "river-jdbc", "river-engine-api", "river-engine", "river-server"
  )
)

// Project dependencies are compile-private unless a current River consumer
// must compile against a type exposed by a dependency. Keep this allowset exact:
// adding an entry changes downstream compile visibility and requires a
// compile-visibility test.
val approvedApiDependencies = mapOf(
  "river-engine-api" to setOf("river-base"),
  "river-protocol" to setOf("river-base", "river-engine-api"),
  "river-client" to setOf("river-base", "river-engine-api")
)

subprojects {
  apply(plugin = "java-library")

  group = rootProject.group
  version = rootProject.version

  extensions.configure<JavaPluginExtension> {
    toolchain {
      languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
  }

  tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
  }

  tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    failFast = true
  }

  tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
  }

  dependencies {
    "testImplementation"("org.junit.jupiter:junit-jupiter:5.13.4")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
  }

  declaredDependencies.getOrDefault(name, emptySet()).forEach { dependencyName ->
    val configuration = if (
      dependencyName in approvedApiDependencies.getOrDefault(name, emptySet())
    ) {
      "api"
    } else {
      "implementation"
    }
    dependencies.add(configuration, dependencies.project(":$dependencyName"))
  }

}

val checkedTextExtensions = setOf(
  "java", "kt", "kts", "gradle", "xml", "yml", "yaml", "json",
  "properties", "md", "sh", ""
)
val indentedExtensions = setOf(
  "java", "kt", "kts", "gradle", "xml", "yml", "yaml", "sh", ""
)
val extensionlessPolicyFiles = setOf("gradlew", "verify", "verify-clean-checkout")
val hotPathPackagePrefixes = setOf(
  "io.riverdb.observability.api.event",
  "io.riverdb.wal.append",
  "io.riverdb.buffer.cache",
  "io.riverdb.storage.access",
  "io.riverdb.tx.commit",
  "io.riverdb.exec.vector"
)
fun hotMethod(
  className: String,
  methodName: String,
  descriptor: String
) = HotPathBytecodePolicy.MethodScope(
  className.replace('.', '/'),
  methodName,
  descriptor
)

val eventPackage = "io.riverdb.observability.api.event"
val eventBinaryPackage = eventPackage.replace('.', '/')
val diagnosticEventDescriptor = "L$eventBinaryPackage/DiagnosticEvent;"
val diagnosticContextDescriptor = "L$eventBinaryPackage/DiagnosticContext;"
val eventPublishResultDescriptor = "L$eventBinaryPackage/EventPublishResult;"
val eventPollResultDescriptor = "L$eventBinaryPackage/EventPollResult;"
val severityDescriptor = "L$eventBinaryPackage/Severity;"
val statusCodeDescriptor = "Lio/riverdb/base/error/StatusCode;"
val localWalPackage = "io.riverdb.wal.local"
val localWalDescriptor = "Lio/riverdb/wal/local/LocalWal;"
val localWalReservationDescriptor = "Lio/riverdb/wal/local/LocalWalReservation;"
val localWalAppendResultDescriptor = "Lio/riverdb/wal/local/LocalWalAppendResult;"
val localWalForceResultDescriptor = "Lio/riverdb/wal/local/LocalWalForceResult;"
val localWalReadResultDescriptor = "Lio/riverdb/wal/local/LocalWalReadResult;"
val walRecordHeaderDescriptor = "Lio/riverdb/format/wal/WalRecordHeader;"
val databaseIncarnationDescriptor = "Lio/riverdb/base/id/DatabaseIncarnation;"
val walGenerationDescriptor = "Lio/riverdb/base/id/WalGeneration;"
val pageHeaderDescriptor = "Lio/riverdb/format/page/PageHeader;"
val pageUpdateDescriptor = "Lio/riverdb/engine/page/PageUpdate;"
val heapInsertResultDescriptor = "Lio/riverdb/storage/heap/HeapInsertResult;"
val heapRowResultDescriptor = "Lio/riverdb/storage/heap/HeapRowResult;"
val heapScanCursorDescriptor = "Lio/riverdb/storage/heap/HeapScanCursor;"
val btreeLookupResultDescriptor = "Lio/riverdb/storage/btree/BTreeLookupResult;"
val btreeSplitResultDescriptor = "Lio/riverdb/storage/btree/BTreeSplitResult;"
val transactionDescriptor = "Lio/riverdb/tx/Transaction;"
val commitSequenceSourceDescriptor = "Lio/riverdb/tx/CommitSequenceSource;"
val transactionParticipantDescriptor = "Lio/riverdb/tx/TransactionCommitParticipant;"
val transactionGroupParticipantDescriptor =
  "Lio/riverdb/tx/TransactionGroupCommitParticipant;"
val transactionOutcomeDescriptor = "Lio/riverdb/tx/api/TransactionOutcome;"
val isolationLevelDescriptor = "Lio/riverdb/tx/api/IsolationLevel;"
val lockModeDescriptor = "Lio/riverdb/tx/api/lock/LockMode;"
val lockScopeDescriptor = "Lio/riverdb/tx/api/lock/LockScope;"
val lockTokenDescriptor = "Lio/riverdb/tx/api/lock/LockToken;"
val indexedCommitResultDescriptor = "Lio/riverdb/engine/table/IndexedCommitResult;"
val indexedMutationTargetDescriptor = "Lio/riverdb/engine/table/IndexedMutationTarget;"
val indexedTableDescriptor = "Lio/riverdb/engine/table/IndexedTable;"
val pendingMutationBufferDescriptor =
  "Lio/riverdb/engine/table/PendingMutationBuffer;"
val indexedScanCursorDescriptor = "Lio/riverdb/engine/table/IndexedScanCursor;"
val indexedScanResultDescriptor = "Lio/riverdb/engine/table/IndexedScanResult;"
val indexedRowDirectoryFrameDescriptor =
  "Lio/riverdb/engine/table/IndexedRowDirectory\$DirectoryFrame;"
val indexedVersionDirectoryFrameDescriptor =
  "Lio/riverdb/engine/table/IndexedVersionDirectory\$VersionFrame;"
val relationalScanCursorDescriptor = "Lio/riverdb/engine/relational/RelationalScanCursor;"
val relationalScanResultDescriptor = "Lio/riverdb/engine/relational/RelationalScanResult;"
val sqlCommandDescriptor = "Lio/riverdb/sql/SqlCommand;"
val sqlQueryDescriptor = "Lio/riverdb/sql/SqlQuery;"
val sqlExecutionResultDescriptor = "Lio/riverdb/engine/sql/SqlExecutionResult;"
val sqlScanCursorDescriptor = "Lio/riverdb/engine/sql/SqlScanCursor;"
val sqlScanRowResultDescriptor = "Lio/riverdb/engine/sql/SqlScanRowResult;"
val engineCommandResultDescriptor = "Lio/riverdb/engine/api/CommandResult;"
val engineQueryOpenResultDescriptor = "Lio/riverdb/engine/api/QueryOpenResult;"
val engineRowResultDescriptor = "Lio/riverdb/engine/api/RowResult;"
val exactDecimalLongValueDescriptor =
  "Lio/riverdb/base/type/ExactDecimal\$LongValue;"
val exactDecimalWideScratchDescriptor =
  "Lio/riverdb/base/type/ExactDecimal\$WideScratch;"
val schemaTableDescriptor = "Lio/riverdb/engine/schema/TableDescriptor;"
val sqlValueBufferDescriptor = "Lio/riverdb/base/type/SqlValueBuffer;"
val storedTableRowEncodeResultDescriptor =
  "Lio/riverdb/engine/row/StoredTableRowEncodeResult;"
val logicalRowIdReservationDescriptor =
  "Lio/riverdb/engine/row/LogicalRowIdReservation;"
val byteBufferDescriptor = "Ljava/nio/ByteBuffer;"
val crc32cDescriptor = "Ljava/util/zip/CRC32C;"
val longArrayDescriptor = "[J"
val intArrayDescriptor = "[I"
val transactionArrayDescriptor = "[Lio/riverdb/tx/Transaction;"
val transactionOutcomeArrayDescriptor =
  "[Lio/riverdb/tx/api/TransactionOutcome;"
val liveHotPathMethods = setOf(
  hotMethod(
    "$eventPackage.BoundedEventRing",
    "isEnabled",
    "($severityDescriptor)Z"
  ),
  hotMethod(
    "$eventPackage.BoundedEventRing",
    "publish",
    "($diagnosticEventDescriptor)$eventPublishResultDescriptor"
  ),
  hotMethod(
    "$eventPackage.BoundedEventRing",
    "poll",
    "($diagnosticEventDescriptor)$eventPollResultDescriptor"
  ),
  hotMethod(
    "$eventPackage.BoundedEventRing",
    "onSaturation",
    "()$eventPublishResultDescriptor"
  ),
  hotMethod("$eventPackage.BoundedEventRing", "isConsumerThread", "()Z"),
  hotMethod(
    "$eventPackage.LevelGatedDiagnosticSink",
    "isEnabled",
    "($severityDescriptor)Z"
  ),
  hotMethod(
    "$eventPackage.LevelGatedDiagnosticSink",
    "publish",
    "($diagnosticEventDescriptor)$eventPublishResultDescriptor"
  ),
  hotMethod(
    "$eventPackage.DiagnosticEvent",
    "reset",
    "()$diagnosticEventDescriptor"
  ),
  hotMethod(
    "$eventPackage.DiagnosticEvent",
    "set",
    "(L$eventBinaryPackage/EventTypeId;$severityDescriptor"
        + "JJ$diagnosticContextDescriptor"
        + "JJJJ)$diagnosticEventDescriptor"
  ),
  hotMethod(
    "$eventPackage.DiagnosticEvent",
    "copyFrom",
    "($diagnosticEventDescriptor)$diagnosticEventDescriptor"
  ),
  hotMethod(
    "$eventPackage.DiagnosticContext",
    "reset",
    "()$diagnosticContextDescriptor"
  ),
  hotMethod(
    "$eventPackage.DiagnosticContext",
    "copyFrom",
    "($diagnosticContextDescriptor)$diagnosticContextDescriptor"
  ),
  hotMethod(
    "$eventPackage.Severity",
    "isEnabledAt",
    "($severityDescriptor)Z"
  ),
  hotMethod(
    "$localWalPackage.LocalWal",
    "reserve",
    "(I$localWalReservationDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "$localWalPackage.LocalWal",
    "publish",
    "($localWalReservationDescriptor"
        + "JJIII$localWalAppendResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "$localWalPackage.LocalWal",
    "appendUnforced",
    "($localWalReservationDescriptor"
        + "JJIII$localWalAppendResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "$localWalPackage.LocalWal",
    "forcePending",
    "($localWalForceResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "$localWalPackage.DurableWalQuorum",
    "replicateForcedBatch",
    "(${localWalDescriptor}I)$statusCodeDescriptor"
  ),
  hotMethod(
    "$localWalPackage.LocalWal",
    "readForcedRecord",
    "(I$localWalReadResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "$localWalPackage.LocalWal",
    "releaseForcedBatch",
    "()$statusCodeDescriptor"
  ),
  hotMethod(
    "$localWalPackage.LocalWal",
    "read",
    "(J$localWalReadResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "$localWalPackage.LocalWal",
    "validDecision",
    "(JJI)Z"
  ),
  hotMethod(
    "io.riverdb.format.wal.WalRecordCodec",
    "encodeReserved",
    "(JJJIIII$byteBufferDescriptor$crc32cDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.format.wal.WalRecordCodec",
    "decodeHeader",
    "($byteBufferDescriptor$walRecordHeaderDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.format.wal.WalRecordCodec",
    "validate",
    "($byteBufferDescriptor$walRecordHeaderDescriptor$crc32cDescriptor)"
        + statusCodeDescriptor
  ),
  hotMethod(
    "io.riverdb.format.wal.WalRecordCodec",
    "checksum",
    "($byteBufferDescriptor" + "I$crc32cDescriptor)I"
  ),
  hotMethod(
    "io.riverdb.format.page.PageCodec",
    "encode",
    "($databaseIncarnationDescriptor$walGenerationDescriptor"
        + "JJJJI$byteBufferDescriptor$crc32cDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedPreparedWriteEncoder",
    "appendInserts",
    "(JJ$pendingMutationBufferDescriptor$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedPreparedWriteEncoder",
    "appendMutations",
    "(JJ$pendingMutationBufferDescriptor$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedPreparedCommitGroup",
    "force",
    "()$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedPreparedCommitGroup",
    "publish",
    "(${walGenerationDescriptor}J)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedGroupCommitCoordinator",
    "process",
    "(I)V"
  ),
  hotMethod(
    "io.riverdb.tx.TransactionManager",
    "beginCommitGroup",
    "($transactionArrayDescriptor" + "I)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.tx.TransactionManager",
    "publishCommitGroup",
    "($transactionArrayDescriptor$transactionOutcomeArrayDescriptor"
        + "$longArrayDescriptor" + "I$transactionGroupParticipantDescriptor)"
        + statusCodeDescriptor
  ),
  hotMethod(
    "io.riverdb.format.page.PageCodec",
    "encodeAt",
    "($databaseIncarnationDescriptor$walGenerationDescriptor"
        + "JJJJI$byteBufferDescriptor" + "I$crc32cDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.format.page.PageCodec",
    "validate",
    "($byteBufferDescriptor$pageHeaderDescriptor$crc32cDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.format.page.PageCodec",
    "validateAt",
    "($byteBufferDescriptor" + "I$pageHeaderDescriptor$crc32cDescriptor)"
        + statusCodeDescriptor
  ),
  hotMethod(
    "io.riverdb.format.page.PageCodec",
    "checksum",
    "($byteBufferDescriptor" + "I$crc32cDescriptor)I"
  ),
  hotMethod(
    "io.riverdb.engine.page.SinglePageStore",
    "beginUpdate",
    "(I$pageUpdateDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.SinglePageStore",
    "commit",
    "($pageUpdateDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.SinglePageStore",
    "beginUpdateFromCurrent",
    "($pageUpdateDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.SinglePageStore",
    "commit",
    "($pageUpdateDescriptor" + "JJI)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.SinglePageStore",
    "flush",
    "()$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.storage.heap.HeapPage",
    "validate",
    "($byteBufferDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.storage.heap.HeapPage",
    "insert",
    "($byteBufferDescriptor$byteBufferDescriptor$heapInsertResultDescriptor)"
        + statusCodeDescriptor
  ),
  hotMethod(
    "io.riverdb.storage.heap.HeapPage",
    "insertFrom",
    "($byteBufferDescriptor$byteBufferDescriptor"
        + "II$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.storage.heap.HeapPage",
    "canInsert",
    "($byteBufferDescriptor" + "I)Z"
  ),
  hotMethod(
    "io.riverdb.storage.heap.HeapPage",
    "fetch",
    "($byteBufferDescriptor" + "I$heapRowResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.storage.heap.HeapPage",
    "availableBytes",
    "($byteBufferDescriptor)I"
  ),
  hotMethod(
    "io.riverdb.storage.heap.HeapPage",
    "next",
    "($byteBufferDescriptor$heapScanCursorDescriptor$heapRowResultDescriptor)"
        + statusCodeDescriptor
  ),
  hotMethod(
    "io.riverdb.engine.table.SinglePageTable",
    "insert",
    "(J$byteBufferDescriptor$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.SinglePageTable",
    "fetch",
    "(I$heapRowResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.SinglePageTable",
    "next",
    "($heapScanCursorDescriptor$heapRowResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.storage.btree.BTreePage",
    "lookupLeaf",
    "($byteBufferDescriptor" + "JJ$btreeLookupResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod("io.riverdb.base.key.OrderedKey", "compare", "(JJJJ)I"),
  hotMethod("io.riverdb.base.key.OrderedKey", "lessThan", "(JJJJ)Z"),
  hotMethod("io.riverdb.base.key.OrderedKey", "equal", "(JJJJ)Z"),
  hotMethod(
    "io.riverdb.storage.btree.BTreePage",
    "childForKey",
    "($byteBufferDescriptor" + "JJ)I"
  ),
  hotMethod(
    "io.riverdb.storage.btree.BTreePage",
    "insertLeaf",
    "($byteBufferDescriptor" + "JJJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.storage.btree.BTreePage",
    "updateLeaf",
    "($byteBufferDescriptor" + "JJJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.storage.btree.BTreePage",
    "splitLeaf",
    "($byteBufferDescriptor$byteBufferDescriptor"
        + "IJJJ$btreeSplitResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.storage.btree.BTreePage",
    "insertInternal",
    "($byteBufferDescriptor" + "JJI)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedLogicalCommitter",
    "commitInsert",
    "(JJJJ$byteBufferDescriptor$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedLogicalCommitter",
    "commitInsertBatch",
    "(JJ$longArrayDescriptor$longArrayDescriptor$byteBufferDescriptor"
        + "I${intArrayDescriptor}I$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedLogicalCommitter",
    "commitMutations",
    "(JJ$intArrayDescriptor$longArrayDescriptor$longArrayDescriptor$intArrayDescriptor"
        + "$byteBufferDescriptor" + "I${intArrayDescriptor}I"
        + "$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "applyInsertOperation",
    "($byteBufferDescriptor" + "JJJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "validateNewIndexEntry",
    "(JJI)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "validateNewIndexEntryAt",
    "(IJJI)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "validateNewIndexEntryIn",
    "($byteBufferDescriptor" + "JJI)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "validateMutationTarget",
    "(IJJJI)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "validateMutationTargetAt",
    "(IIJJJI)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "validateMutationTargetIn",
    "($byteBufferDescriptor" + "IJJJI)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "validateVacuumHead",
    "(JJJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "validatedLeafPageId",
    "()I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "applyInsertBatchOperation",
    "($byteBufferDescriptor" + "JJJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableWalApplier",
    "containsEarlierInsertKey",
    "($byteBufferDescriptor" + "IJJ)Z"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableWalApplier",
    "countEarlierInsertEntriesInLeaf",
    "($byteBufferDescriptor" + "II)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "applyMutationBatchOperation",
    "($byteBufferDescriptor" + "JJJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableWalApplier",
    "containsEarlierMutationKey",
    "($byteBufferDescriptor" + "IJJ)Z"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableWalApplier",
    "countEarlierMutationInsertsInLeaf",
    "($byteBufferDescriptor" + "II)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "rowCommitSequence",
    "(J)J"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "previousRowId",
    "(J)J"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "isDeletedRow",
    "(J)Z"
  ),
  hotMethod("io.riverdb.engine.table.IndexedRowDirectory", "pageId", "(J)I"),
  hotMethod("io.riverdb.engine.table.IndexedRowDirectory", "slot", "(J)I"),
  hotMethod("io.riverdb.engine.table.IndexedRowDirectory", "set", "(JII)V"),
  hotMethod("io.riverdb.engine.table.IndexedRowDirectory", "read", "(J)Z"),
  hotMethod(
    "io.riverdb.engine.table.IndexedRowDirectory",
    "frame",
    "(J)$indexedRowDirectoryFrameDescriptor"
  ),
  hotMethod("io.riverdb.engine.table.IndexedRowDirectory", "findFrame", "()I"),
  hotMethod(
    "io.riverdb.engine.table.IndexedVersionState",
    "commitSequence",
    "(JJ)J"
  ),
  hotMethod("io.riverdb.engine.table.IndexedVersionState", "previousRow", "(JJ)J"),
  hotMethod("io.riverdb.engine.table.IndexedVersionState", "isDeleted", "(JJ)Z"),
  hotMethod(
    "io.riverdb.engine.table.IndexedVersionState",
    "recordCommitted",
    "(JJJZ)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedVersionState",
    "recordNewRows",
    "(JJJ)V"
  ),
  hotMethod("io.riverdb.engine.table.IndexedVersionState", "recordOperation", "(JJ)V"),
  hotMethod(
    "io.riverdb.engine.table.IndexedVersionState",
    "applyRecovered",
    "($byteBufferDescriptor" + "IJIJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedVersionState",
    "recordVacuumDeleted",
    "(JZ)V"
  ),
  hotMethod("io.riverdb.engine.table.IndexedVersionState", "publishVacuum", "(JJ)V"),
  hotMethod("io.riverdb.engine.table.IndexedVersionState", "cancelVacuum", "(J)V"),
  hotMethod("io.riverdb.engine.table.IndexedVersionState", "visibleRow", "(JJJ)J"),
  hotMethod("io.riverdb.engine.table.IndexedVersionDirectory", "set", "(JJJZ)V"),
  hotMethod(
    "io.riverdb.engine.table.IndexedVersionDirectory",
    "setVacuumDeleted",
    "(JZ)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedVersionDirectory",
    "clearVacuumDeleted",
    "(J)V"
  ),
  hotMethod("io.riverdb.engine.table.IndexedVersionDirectory", "read", "(J)Z"),
  hotMethod("io.riverdb.engine.table.IndexedVersionDirectory", "commitSequence", "()J"),
  hotMethod("io.riverdb.engine.table.IndexedVersionDirectory", "previousRowId", "()J"),
  hotMethod("io.riverdb.engine.table.IndexedVersionDirectory", "deleted", "()Z"),
  hotMethod("io.riverdb.engine.table.IndexedVersionDirectory", "vacuumDeleted", "()Z"),
  hotMethod("io.riverdb.engine.table.IndexedVersionDirectory", "write", "(JJJJJ)V"),
  hotMethod(
    "io.riverdb.engine.table.IndexedVersionDirectory",
    "frame",
    "(J)$indexedVersionDirectoryFrameDescriptor"
  ),
  hotMethod("io.riverdb.engine.table.IndexedVersionDirectory", "findFrame", "()I"),
  hotMethod(
    "io.riverdb.engine.table.IndexedPageSet",
    "stageExisting",
    "(II)$byteBufferDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedPageSet",
    "operationPayload",
    "(I)$byteBufferDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedPageSet",
    "currentPayload",
    "(I)$byteBufferDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedPageSet",
    "stageNew",
    "(II)$byteBufferDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedPageSet",
    "publish",
    "(JJ)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedPageSet",
    "copyPage",
    "($byteBufferDescriptor$byteBufferDescriptor)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedPageSet",
    "currentPayloadUnchecked",
    "(I)$byteBufferDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedPageSet",
    "copyStagedToRecord",
    "(I${byteBufferDescriptor}I)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedPageSet",
    "installFromRecord",
    "($byteBufferDescriptor" + "IIJJ)V"
  ),
  hotMethod("io.riverdb.engine.table.IndexedPageSet", "isPresent", "(I)Z"),
  hotMethod("io.riverdb.engine.table.IndexedPageSet", "isStaged", "(I)Z"),
  hotMethod("io.riverdb.engine.table.IndexedPageSet", "isDirty", "(I)Z"),
  hotMethod("io.riverdb.engine.table.IndexedPageSet", "recordStart", "(I)J"),
  hotMethod("io.riverdb.engine.table.IndexedPageSet", "recordEnd", "(I)J"),
  hotMethod("io.riverdb.engine.table.IndexedPageSet", "changedPageCount", "()I"),
  hotMethod("io.riverdb.engine.table.IndexedPageSet", "changedPageId", "(I)I"),
  hotMethod("io.riverdb.engine.table.IndexedPageSet", "highestPageId", "()I"),
  hotMethod("io.riverdb.engine.table.IndexedPageSet", "stagedCopyBytes", "()J"),
  hotMethod("io.riverdb.engine.table.IndexedPageSet", "resetChanges", "()V"),
  hotMethod(
    "io.riverdb.engine.table.IndexedPageSet",
    "markCurrentChanged",
    "(IJJ)V"
  ),
  hotMethod("io.riverdb.engine.table.IndexedPageSet", "installPresent", "(I)V"),
  hotMethod(
    "io.riverdb.engine.table.IndexedPageSet",
    "installChanged",
    "(IJJ)V"
  ),
  hotMethod("io.riverdb.engine.table.IndexedPageSet", "markClean", "(I)V"),
  hotMethod("io.riverdb.engine.table.IndexedPageSet", "markRebased", "(I)V"),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "hasCommonHeader",
    "($byteBufferDescriptor)Z"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "operationType",
    "($byteBufferDescriptor)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "pageOperationBytes",
    "(II)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "insertOperationBytes",
    "(I)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "insertBatchEntryBytes",
    "(I)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "mutationBatchEntryBytes",
    "(I)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "vacuumEntryBytes",
    "(I)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "encodePageOperationHeader",
    "($byteBufferDescriptor" + "II)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "encodePageOperationVersion",
    "($byteBufferDescriptor" + "IJZ)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "encodeInsertHeader",
    "($byteBufferDescriptor" + "JJJI)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "encodeInsertBatchHeader",
    "($byteBufferDescriptor" + "I)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "encodeInsertBatchEntry",
    "($byteBufferDescriptor" + "IJJJI)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "encodeMutationBatchHeader",
    "($byteBufferDescriptor" + "I)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "encodeMutationBatchEntry",
    "($byteBufferDescriptor" + "IIJJJJI)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "encodeVacuumChunkHeader",
    "($byteBufferDescriptor" + "JJIII)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "encodeVacuumEntry",
    "($byteBufferDescriptor" + "IJJJIZ)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "encodeVacuumCommit",
    "($byteBufferDescriptor" + "JIJ)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "validatePageOperation",
    "($byteBufferDescriptor" + "II)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "validateInsert",
    "($byteBufferDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "validateInsertBatch",
    "($byteBufferDescriptor" + "I)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "validateMutationBatch",
    "($byteBufferDescriptor" + "I)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "validateVacuumChunk",
    "($byteBufferDescriptor" + "JI)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "validateVacuumCommit",
    "($byteBufferDescriptor" + "JI)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "validInsertBatchEntry",
    "($byteBufferDescriptor" + "I)Z"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "validMutationBatchEntry",
    "($byteBufferDescriptor" + "I)Z"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "validVacuumEntry",
    "($byteBufferDescriptor" + "I)Z"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "validPageOperationVersion",
    "($byteBufferDescriptor" + "I)Z"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "containsEarlierPageId",
    "($intArrayDescriptor" + "II)Z"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "putInt",
    "($byteBufferDescriptor" + "II)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "getInt",
    "($byteBufferDescriptor" + "I)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "putLong",
    "($byteBufferDescriptor" + "IJ)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "getLong",
    "($byteBufferDescriptor" + "I)J"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "encodeCommonHeader",
    "($byteBufferDescriptor" + "I)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "hasOperationType",
    "($byteBufferDescriptor" + "I)Z"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "isMutation",
    "(I)Z"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedWalCodec",
    "checkedVariableBytes",
    "(II)I"
  ),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "pageOperationPageCount", "($byteBufferDescriptor)I"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "pageOperationVersionCount", "($byteBufferDescriptor)I"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "pageOperationPageOffset", "(I)I"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "pageOperationVersionsOffset", "(I)I"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "insertKey", "($byteBufferDescriptor)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "insertSpace", "($byteBufferDescriptor)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "insertRowId", "($byteBufferDescriptor)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "insertRowBytes", "($byteBufferDescriptor)I"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "batchEntryCount", "($byteBufferDescriptor)I"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "insertBatchKey", "($byteBufferDescriptor" + "I)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "insertBatchSpace", "($byteBufferDescriptor" + "I)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "insertBatchRowId", "($byteBufferDescriptor" + "I)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "insertBatchRowBytes", "($byteBufferDescriptor" + "I)I"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "mutationOperation", "($byteBufferDescriptor" + "I)I"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "mutationKey", "($byteBufferDescriptor" + "I)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "mutationSpace", "($byteBufferDescriptor" + "I)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "mutationRowId", "($byteBufferDescriptor" + "I)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "mutationPreviousRowId", "($byteBufferDescriptor" + "I)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "mutationRowBytes", "($byteBufferDescriptor" + "I)I"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "encodedRowBytes", "($byteBufferDescriptor" + "II)I"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "pageVersionPreviousRowId", "($byteBufferDescriptor" + "I)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "pageVersionDeleted", "($byteBufferDescriptor" + "I)Z"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "vacuumRetainedRows", "($byteBufferDescriptor)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "vacuumFirstRow", "($byteBufferDescriptor)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "vacuumRowCount", "($byteBufferDescriptor)I"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "vacuumChunk", "($byteBufferDescriptor)I"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "vacuumChunkCount", "($byteBufferDescriptor)I"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "vacuumEntryKey", "($byteBufferDescriptor" + "I)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "vacuumEntrySpace", "($byteBufferDescriptor" + "I)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "vacuumEntryRowId", "($byteBufferDescriptor" + "I)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "vacuumEntryRowBytes", "($byteBufferDescriptor" + "I)I"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "vacuumEntryDeleted", "($byteBufferDescriptor" + "I)Z"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "vacuumCommitRowsBefore", "($byteBufferDescriptor)J"),
  hotMethod("io.riverdb.engine.table.IndexedWalCodec", "vacuumCommitChunkCount", "($byteBufferDescriptor)I"),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "stageVersionRow",
    "($byteBufferDescriptor" + "IIJZ$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod("io.riverdb.engine.table.IndexedTableKernel", "canAppendRow", "(I)Z"),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "fetchRow",
    "(J$heapRowResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "rowLength",
    "(J)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "copyRowTo",
    "(J$byteBufferDescriptor" + "I)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "canAppendRows",
    "(${intArrayDescriptor}I)Z"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "canAppendEncodedRows",
    "($byteBufferDescriptor" + "IIII)Z"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "appendCurrentRow",
    "($byteBufferDescriptor" + "IIJJJJJZ)$statusCodeDescriptor"
  ),
  hotMethod("io.riverdb.engine.table.IndexedTableKernel", "indexedEntryCount", "()I"),
  hotMethod("io.riverdb.engine.table.IndexedTableKernel", "vacuumChunkCount", "()I"),
  hotMethod("io.riverdb.engine.table.IndexedTableKernel", "vacuumChunkRowCount", "(J)I"),
  hotMethod("io.riverdb.engine.table.IndexedTableKernel", "vacuumChunkPayloadBytes", "(JI)I"),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "encodeVacuumChunk",
    "($byteBufferDescriptor" + "JJIIII)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "beginVacuumApply",
    "()$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "applyVacuumEntry",
    "($byteBufferDescriptor" + "IJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "validateCurrentPage",
    "(I)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "validateAppliedPages",
    "($intArrayDescriptor" + "I)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableStore",
    "insert",
    "(JJJ$byteBufferDescriptor$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.storage.heap.HeapRowResult",
    "copyFrom",
    "($heapRowResultDescriptor)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedScanResult",
    "copyFrom",
    "($indexedScanResultDescriptor)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableStore",
    "commitInsert",
    "(JJJ$byteBufferDescriptor$indexedCommitResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableStore",
    "commitInserts",
    "(J$longArrayDescriptor$longArrayDescriptor$byteBufferDescriptor"
        + "I${intArrayDescriptor}I$indexedCommitResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableStore",
    "commitMutations",
    "(J$intArrayDescriptor$longArrayDescriptor$longArrayDescriptor$intArrayDescriptor"
        + "$byteBufferDescriptor" + "I${intArrayDescriptor}I"
        + "$indexedCommitResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableStore",
    "commitMutations",
    "(J$pendingMutationBufferDescriptor$indexedCommitResultDescriptor)"
        + statusCodeDescriptor
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "prepareMutation",
    "(JJJ$indexedMutationTargetDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "prepareInsert",
    "(JJJ$indexedMutationTargetDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedMutationTarget",
    "rowId",
    "()J"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedMutationTarget",
    "set",
    "(J)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedMutationTarget",
    "reset",
    "()V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableStore",
    "insertCommitted",
    "(JJJJ$byteBufferDescriptor$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "stageInsertBatch",
    "($longArrayDescriptor$longArrayDescriptor$byteBufferDescriptor"
        + "I${intArrayDescriptor}I$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "stageMutationBatch",
    "($intArrayDescriptor$longArrayDescriptor$longArrayDescriptor$intArrayDescriptor"
        + "$byteBufferDescriptor" + "I${intArrayDescriptor}I"
        + "$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "stageInsert",
    "(IJJ$byteBufferDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableIndexTree",
    "splitAndInsert",
    "(I$byteBufferDescriptor" + "JJJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "findOperationLeafPageId",
    "(JJ)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableValidator",
    "versionRowsInLeaf",
    "($byteBufferDescriptor)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableStore",
    "fetchByKey",
    "(JJ$heapRowResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "fetchByKeyAt",
    "(JJJ$heapRowResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.tx.TransactionManager",
    "begin",
    "($isolationLevelDescriptor" + "J$transactionDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.tx.TransactionManager",
    "begin",
    "($isolationLevelDescriptor$commitSequenceSourceDescriptor$transactionDescriptor)"
        + statusCodeDescriptor
  ),
  hotMethod(
    "io.riverdb.tx.TransactionManager",
    "refreshReadCommitted",
    "($transactionDescriptor" + "J)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.tx.TransactionManager",
    "refreshReadCommitted",
    "($transactionDescriptor$commitSequenceSourceDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.tx.TransactionManager",
    "commit",
    "($transactionDescriptor$transactionParticipantDescriptor$transactionOutcomeDescriptor)"
        + statusCodeDescriptor
  ),
  hotMethod(
    "io.riverdb.tx.TransactionManager",
    "abort",
    "($transactionDescriptor$transactionOutcomeDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.tx.LockManager",
    "tryAcquire",
    "(J$lockScopeDescriptor" + "JJJJ$lockModeDescriptor"
        + "JJ$lockTokenDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.tx.LockManager",
    "release",
    "($lockTokenDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.tx.LockManager",
    "upgrade",
    "($lockTokenDescriptor$lockModeDescriptor" + "JJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.tx.LockManager",
    "validToken",
    "($lockTokenDescriptor" + "I)Z"
  ),
  hotMethod(
    "io.riverdb.tx.LockManager",
    "conflicts",
    "(II)Z"
  ),
  hotMethod(
    "io.riverdb.tx.TransactionManager",
    "tryAcquireKey",
    "($transactionDescriptor" + "JJ$lockTokenDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.tx.TransactionManager",
    "release",
    "($lockTokenDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.tx.TransactionManager",
    "tryAcquireSharedKey",
    "($transactionDescriptor" + "JJ$lockTokenDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.tx.TransactionManager",
    "tryAcquireSharedRange",
    "($transactionDescriptor" + "JJJJ$lockTokenDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.tx.TransactionManager",
    "upgradeKey",
    "($transactionDescriptor$lockTokenDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.tx.TransactionManager",
    "commitReadOnly",
    "($transactionDescriptor$transactionOutcomeDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "begin",
    "($isolationLevelDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.base.type.ExactDecimal",
    "add",
    "(JIJIZI$exactDecimalLongValueDescriptor"
        + "$exactDecimalWideScratchDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.base.type.ExactDecimal",
    "multiply",
    "(JIJII$exactDecimalLongValueDescriptor"
        + "$exactDecimalWideScratchDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.base.type.ExactDecimal",
    "divide",
    "(JIJII$exactDecimalLongValueDescriptor"
        + "$exactDecimalWideScratchDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.base.type.ExactDecimal",
    "remainder",
    "(JIJII$exactDecimalLongValueDescriptor"
        + "$exactDecimalWideScratchDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.base.type.ExactDecimal",
    "quantize",
    "(JIIZZ$exactDecimalLongValueDescriptor"
        + "$exactDecimalWideScratchDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.base.type.ExactDecimal",
    "compare",
    "(JIJI)I"
  ),
  hotMethod(
    "io.riverdb.base.type.ExactDecimal",
    "average",
    "(JJJII$exactDecimalLongValueDescriptor"
        + "$exactDecimalWideScratchDescriptor)Z"
  ),
  hotMethod(
    "io.riverdb.sql.SqlParser",
    "parse",
    "(Ljava/lang/String;$sqlCommandDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.sql.SqlParser",
    "parseQuery",
    "(Ljava/lang/String;$sqlQueryDescriptor$sqlCommandDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.sql.SqlSession",
    "execute",
    "(Ljava/lang/String;$sqlExecutionResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTable",
    "beginScan",
    "(JJJJJ$indexedScanCursorDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "nextScan",
    "($indexedScanCursorDescriptor$indexedScanResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTable",
    "closeScan",
    "($indexedScanCursorDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "beginScan",
    "(JJJJ$indexedScanCursorDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "acquireSharedRangeForScan",
    "(JJJJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "nextScan",
    "($indexedScanCursorDescriptor$indexedScanResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.relational.RelationalSession",
    "beginScan",
    "(Lio/riverdb/engine/relational/TableDefinition;$relationalScanCursorDescriptor)"
        + statusCodeDescriptor
  ),
  hotMethod(
    "io.riverdb.engine.relational.RelationalSession",
    "nextScan",
    "($relationalScanCursorDescriptor$relationalScanResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.sql.SqlSession",
    "beginScan",
    "(Ljava/lang/String;$sqlScanCursorDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.sql.SqlSession",
    "nextScan",
    "($sqlScanCursorDescriptor$sqlScanRowResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.EmbeddedRiver\$EngineSession",
    "execute",
    "(Ljava/lang/String;$engineCommandResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.EmbeddedRiver\$EngineSession",
    "beginQuery",
    "(Ljava/lang/String;$engineQueryOpenResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.EmbeddedRiver\$EngineSession",
    "copyExecution",
    "($engineCommandResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.EmbeddedRiver\$EngineSession\$EngineQuery",
    "next",
    "($engineRowResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowCodec",
    "encode",
    "($schemaTableDescriptor" + "J$sqlValueBufferDescriptor$byteBufferDescriptor"
        + "I$storedTableRowEncodeResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.row.LogicalRowIdAllocator",
    "reserveInserts",
    "(I$logicalRowIdReservationDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.row.LogicalRowIdAllocator",
    "reserveHeapVersions",
    "(I$logicalRowIdReservationDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.row.LogicalRowIdAllocator",
    "encodeWatermark",
    "($byteBufferDescriptor" + "I$crc32cDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.row.LogicalRowIdAllocator",
    "available",
    "(JI)Z"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowCodec",
    "decode",
    "($schemaTableDescriptor" + "J$byteBufferDescriptor"
        + "II$sqlValueBufferDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowEncoder",
    "encode",
    "($schemaTableDescriptor" + "J$sqlValueBufferDescriptor$byteBufferDescriptor"
        + "I$storedTableRowEncodeResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowEncoder",
    "validArguments",
    "($schemaTableDescriptor" + "J$sqlValueBufferDescriptor$byteBufferDescriptor" + "I)Z"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowEncoder",
    "checkedLength",
    "($schemaTableDescriptor$sqlValueBufferDescriptor)I"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowEncoder",
    "writeBitmap",
    "($schemaTableDescriptor$sqlValueBufferDescriptor$byteBufferDescriptor" + "I)V"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowEncoder",
    "writeSlots",
    "($schemaTableDescriptor$sqlValueBufferDescriptor$byteBufferDescriptor" + "I)V"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowEncoder",
    "writeFixed",
    "($byteBufferDescriptor" + "IIJ)V"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowEncoder",
    "zero",
    "($byteBufferDescriptor" + "II)V"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowEncoder",
    "fixedEnd",
    "($schemaTableDescriptor)I"
  ),
  hotMethod("io.riverdb.engine.row.StoredTableRowEncoder", "isText", "(I)Z"),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowDecoder",
    "decode",
    "($schemaTableDescriptor" + "J$byteBufferDescriptor"
        + "II$sqlValueBufferDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowDecoder",
    "validArguments",
    "($schemaTableDescriptor" + "J$byteBufferDescriptor"
        + "II$sqlValueBufferDescriptor)Z"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowBodyValidator",
    "validate",
    "($schemaTableDescriptor$byteBufferDescriptor" + "II)I"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowBodyValidator",
    "validateSlot",
    "($schemaTableDescriptor$byteBufferDescriptor" + "IIII)I"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowBodyValidator",
    "canonicalBitmap",
    "($schemaTableDescriptor$byteBufferDescriptor" + "I)Z"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowPublisher",
    "publish",
    "($schemaTableDescriptor$byteBufferDescriptor" + "I$sqlValueBufferDescriptor)"
        + statusCodeDescriptor
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowPublisher",
    "publishSlot",
    "($schemaTableDescriptor$byteBufferDescriptor" + "II$sqlValueBufferDescriptor)"
        + statusCodeDescriptor
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowAccess",
    "nullAt",
    "($byteBufferDescriptor" + "II)Z"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowAccess",
    "fixedValue",
    "($schemaTableDescriptor" + "I$byteBufferDescriptor" + "I)J"
  ),
  hotMethod(
    "io.riverdb.engine.row.StoredTableRowAccess",
    "zero",
    "($byteBufferDescriptor" + "II)Z"
  ),
  hotMethod(
    "io.riverdb.base.type.SqlValueBuffer",
    "setTextBytes",
    "(II$byteBufferDescriptor" + "II)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.base.type.SqlValueBuffer",
    "clearForSize",
    "(I)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.base.type.SqlValueBuffer",
    "setFixed",
    "(IIJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.base.type.SqlValueBuffer",
    "setNull",
    "(II)$statusCodeDescriptor"
  ),
  hotMethod("io.riverdb.base.type.SqlValueBuffer", "count", "()I"),
  hotMethod("io.riverdb.base.type.SqlValueBuffer", "capacity", "()I"),
  hotMethod("io.riverdb.base.type.SqlValueBuffer", "valueAt", "(I)J"),
  hotMethod("io.riverdb.base.type.SqlValueBuffer", "descriptorAt", "(I)I"),
  hotMethod("io.riverdb.base.type.SqlValueBuffer", "isNull", "(I)Z"),
  hotMethod("io.riverdb.base.type.SqlValueBuffer", "nullWord", "(I)J"),
  hotMethod("io.riverdb.base.type.SqlValueBuffer", "textByteLengthAt", "(I)I"),
  hotMethod("io.riverdb.base.type.SqlValueBuffer", "textCapacity", "()I"),
  hotMethod("io.riverdb.base.type.SqlValueBuffer", "textMaximumBytes", "()I"),
  hotMethod("io.riverdb.base.type.SqlValueBuffer", "textByteAt", "(II)I"),
  hotMethod(
    "io.riverdb.base.type.SqlValueBuffer",
    "publish",
    "(IIJII)V"
  ),
  hotMethod("io.riverdb.base.type.SqlValueBuffer", "validIndex", "(I)Z"),
  hotMethod("io.riverdb.base.type.SqlValueBuffer", "unassigned", "(I)Z"),
  hotMethod("io.riverdb.base.type.SqlValueBuffer", "isText", "(I)Z"),
  hotMethod(
    "io.riverdb.base.text.Utf8TextArena",
    "append",
    "($byteBufferDescriptor" + "III)$statusCodeDescriptor"
  ),
  hotMethod("io.riverdb.base.text.Utf8TextArena", "byteAt", "(I)I"),
  hotMethod("io.riverdb.base.text.Utf8TextArena", "maximumBytes", "()I"),
  hotMethod(
    "io.riverdb.base.text.Utf8TextArena",
    "ensureCapacity",
    "(I)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.base.text.Utf8TextArena",
    "grow",
    "(II)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingMutationAdmission",
    "reserveAndLock",
    "(Lio/riverdb/engine/table/IndexedTransactionSession;JJI)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "reservePending",
    "(II)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "reservePending",
    "([III)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "insert",
    "(JJ$byteBufferDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionWriteSet",
    "insert",
    "(JJ$byteBufferDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "update",
    "(JJ$byteBufferDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionWriteSet",
    "update",
    "(JJ$byteBufferDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "delete",
    "(JJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionWriteSet",
    "delete",
    "(JJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionWriteSet",
    "validRow",
    "(J$byteBufferDescriptor)Z"
  ),
  hotMethod("io.riverdb.engine.table.IndexedTransactionWriteSet", "full", "()Z"),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "appendPending",
    "(IJJJ$byteBufferDescriptor" + "IIZ)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingMutationBuffer",
    "reserve",
    "(II)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingMutationBuffer",
    "reserve",
    "([III)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingMutationBuffer",
    "append",
    "(IJJJ$byteBufferDescriptor" + "II)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingMutationBuffer",
    "appendDeletion",
    "(IJJJ)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingMutationBuffer",
    "copyRowTo",
    "(I$byteBufferDescriptor" + "I)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingMutationBuffer",
    "insertRowInto",
    "(I$byteBufferDescriptor$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingMutationBuffer",
    "setRowResult",
    "(I$heapRowResultDescriptor)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingMutationBuffer",
    "containsNonInsertMutation",
    "()Z"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingMutationBuffer",
    "findLatestIndex",
    "(JJ)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingMutationBuffer",
    "nextIndex",
    "($indexedScanCursorDescriptor)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingMutationBuffer",
    "truncate",
    "(I)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingMutationBuffer",
    "compact",
    "()V"
  ),
  hotMethod("io.riverdb.engine.table.PendingMutationBuffer", "count", "()I"),
  hotMethod("io.riverdb.engine.table.PendingMutationBuffer", "rowStride", "()I"),
  hotMethod("io.riverdb.engine.table.PendingMutationBuffer", "operationAt", "(I)I"),
  hotMethod("io.riverdb.engine.table.PendingMutationBuffer", "keyAt", "(I)J"),
  hotMethod("io.riverdb.engine.table.PendingMutationBuffer", "spaceAt", "(I)J"),
  hotMethod("io.riverdb.engine.table.PendingMutationBuffer", "previousRowIdAt", "(I)J"),
  hotMethod("io.riverdb.engine.table.PendingMutationBuffer", "rowLengthAt", "(I)I"),
  hotMethod(
    "io.riverdb.engine.table.PendingRowArena",
    "reserveRow",
    "(I)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingRowArena",
    "reserveRows",
    "([III)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingRowArena",
    "append",
    "($byteBufferDescriptor" + "II)I"
  ),
  hotMethod("io.riverdb.engine.table.PendingRowArena", "appendDeletion", "()I"),
  hotMethod(
    "io.riverdb.engine.table.PendingRowArena",
    "copyTo",
    "(II$byteBufferDescriptor" + "I)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingRowArena",
    "insertInto",
    "(II$byteBufferDescriptor$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.PendingRowArena",
    "setResult",
    "(II$heapRowResultDescriptor)V"
  ),
  hotMethod("io.riverdb.engine.table.PendingRowArena", "beginCompaction", "()V"),
  hotMethod("io.riverdb.engine.table.PendingRowArena", "compactRow", "(II)I"),
  hotMethod("io.riverdb.engine.table.PendingRowArena", "finishCompaction", "()V"),
  hotMethod("io.riverdb.engine.table.PendingRowArena", "truncateTo", "(I)V"),
  hotMethod("io.riverdb.engine.table.PendingRowArena", "endOffset", "()I"),
  hotMethod("io.riverdb.engine.table.PendingRowArena", "advanceFor", "(I)V"),
  hotMethod("io.riverdb.engine.table.PendingRowArena", "allocateChunk", "(I)V"),
  hotMethod("io.riverdb.engine.table.PendingRowArena", "encodeOffset", "(II)I"),
  hotMethod("io.riverdb.engine.table.PendingRowArena", "chunkIndex", "(I)I"),
  hotMethod("io.riverdb.engine.table.PendingRowArena", "chunkOffset", "(I)I"),
  hotMethod("io.riverdb.engine.table.HeapPendingRowChunkAllocator", "allocate", "(I)[B"),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "fetchByKey",
    "(JJ$heapRowResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "commit",
    "($transactionOutcomeDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "releaseLocks",
    "()$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "clearWriteSet",
    "()V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "findHeldLock",
    "(JJ)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "acquireExclusiveKey",
    "(JJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "refreshForWrite",
    "()$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "containsNonInsertMutation",
    "()Z"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "commit",
    "(J)$statusCodeDescriptor"
  )
)
val liveHotPathAllowedRules = mapOf(
  hotMethod(
    "$eventPackage.BoundedEventRing",
    "onSaturation",
    "()$eventPublishResultDescriptor"
  ) to setOf(
    HotPathBytecodePolicy.Allowance(
      HotPathBytecodePolicy.Rule.OBJECT_ALLOCATION,
      "new java.lang.MatchException"
    ),
    HotPathBytecodePolicy.Allowance(
      HotPathBytecodePolicy.Rule.EXCEPTION_CONSTRUCTION,
      "new java.lang.MatchException"
    ),
    HotPathBytecodePolicy.Allowance(
      HotPathBytecodePolicy.Rule.EXCEPTION_THROW,
      "athrow"
    )
  ),
  // javac emits one synthetic rethrow so the pinned scan page is always released.
  hotMethod(
    "io.riverdb.engine.table.IndexedTableKernel",
    "nextScan",
    "($indexedScanCursorDescriptor$indexedScanResultDescriptor)$statusCodeDescriptor"
  ) to setOf(
    HotPathBytecodePolicy.Allowance(
      HotPathBytecodePolicy.Rule.EXCEPTION_THROW,
      "athrow"
    )
  ),
  // Cache frames are allocated only on a bounded cold miss and then retained.
  hotMethod(
    "io.riverdb.engine.table.IndexedRowDirectory",
    "frame",
    "(J)$indexedRowDirectoryFrameDescriptor"
  ) to setOf(
    HotPathBytecodePolicy.Allowance(
      HotPathBytecodePolicy.Rule.OBJECT_ALLOCATION,
      "new io.riverdb.engine.table.IndexedRowDirectory\$DirectoryFrame"
    )
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedVersionDirectory",
    "frame",
    "(J)$indexedVersionDirectoryFrameDescriptor"
  ) to setOf(
    HotPathBytecodePolicy.Allowance(
      HotPathBytecodePolicy.Rule.OBJECT_ALLOCATION,
      "new io.riverdb.engine.table.IndexedVersionDirectory\$VersionFrame"
    )
  ),
  // Pending-row chunks are allocated only after bounded reservation and then retained.
  hotMethod(
    "io.riverdb.engine.table.HeapPendingRowChunkAllocator",
    "allocate",
    "(I)[B"
  ) to setOf(
    HotPathBytecodePolicy.Allowance(
      HotPathBytecodePolicy.Rule.ARRAY_ALLOCATION,
      "new byte array"
    )
  ),
  // UTF-8 storage grows only at bounded admission and retains its array and buffer view.
  hotMethod(
    "io.riverdb.base.text.Utf8TextArena",
    "grow",
    "(II)$statusCodeDescriptor"
  ) to setOf(
    HotPathBytecodePolicy.Allowance(
      HotPathBytecodePolicy.Rule.ARRAY_ALLOCATION,
      "new byte array"
    )
  )
)
val inheritedDependencyFixture = configurations.create("policyFixtureInheritedDependency")
val inheritedClasspathFixture = configurations.create("policyFixtureCompileClasspath") {
  extendsFrom(inheritedDependencyFixture)
}
dependencies.add(
  inheritedDependencyFixture.name,
  dependencies.project(mapOf("path" to ":river-base"))
)

val verifySourcePolicy = tasks.register("verifySourcePolicy") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Checks source layout, package boundaries, and production/test separation."

  val sourceFiles = fileTree(rootDir) {
    exclude(".git/**", ".gradle/**", ".river-gradle/**", "**/build/**")
  }
  inputs.files(sourceFiles)

  doLast {
    val checkedFiles = sourceFiles.files
      .filter { file -> file.extension.isNotEmpty() || file.name in extensionlessPolicyFiles }
      .map { it.toPath() }
    val javaSources = sourceFiles.files
      .filter { it.extension.equals("java", ignoreCase = true) }
      .map { file ->
        val sourcePath = file.toPath().toAbsolutePath().normalize()
        val owner = subprojects.firstOrNull { module ->
          sourcePath.startsWith(
            module.projectDir.toPath().toAbsolutePath().normalize()
          )
        }
        val productionSource = owner != null && sourcePath.startsWith(
          owner.projectDir.resolve("src/main/java").toPath().toAbsolutePath().normalize()
        )
        BuildPolicy.JavaSource(
          owner?.name ?: "__root__",
          file.toPath(),
          file.readText(),
          productionSource
        )
      }
    val violations = BuildPolicy.sourceViolations(
      rootDir.toPath(),
      javaSources,
      checkedFiles,
      checkedTextExtensions,
      indentedExtensions,
      hotPathPackagePrefixes
    )
    if (violations.isNotEmpty()) {
      throw GradleException(violations.joinToString(separator = "\n"))
    }
  }
}

val sqlShapeLegacyMatchCeilings = mapOf(
  "river-base" to 4,
  "river-client" to 8,
  "river-engine" to 433,
  "river-engine-api" to 13,
  "river-format" to 4,
  "river-jdbc" to 9,
  "river-protocol" to 59,
  "river-sql" to 11
)
val sqlShapeLegacyPattern = Regex(
  """\b(?:MAXIMUM_COLUMNS|MAXIMUM_JOIN_ROLES|MAXIMUM_ARITY)\b"""
      + """|\b[A-Za-z0-9_]*null_?masks?[A-Za-z0-9_]*\b"""
      + """|\b1L\s*<<\s*\(*\s*(?:[A-Za-z_][A-Za-z0-9_]*\s*\.\s*)*"""
      + """(?:[A-Za-z0-9_]*(?:column|lane|projection)[A-Za-z0-9_]*|index)\b""",
  RegexOption.IGNORE_CASE
)
fun sqlShapeLegacyMatchCount(source: CharSequence): Int =
  sqlShapeLegacyPattern.findAll(source).count()

val verifySqlShapeSourcePolicy = tasks.register("verifySqlShapeSourcePolicy") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Prevents growth of legacy fixed-column and scalar-null-mask source patterns."

  val productionSources = productionModules.associateWith { module ->
    project(":$module").fileTree("src/main/java") { include("**/*.java") }
  }
  inputs.files(productionSources.values)

  doLast {
    val violations = mutableListOf<String>()
    productionModules.forEach { module ->
      val matches = productionSources.getValue(module).files.sumOf { source ->
        sqlShapeLegacyMatchCount(source.readText())
      }
      val ceiling = sqlShapeLegacyMatchCeilings[module] ?: 0
      if (matches > ceiling) {
        violations.add("$module has $matches legacy SQL-shape matches; ceiling is $ceiling")
      }
    }
    if (violations.isNotEmpty()) {
      throw GradleException(violations.joinToString(separator = "\n"))
    }
  }
}

val verifySqlShapeSourcePolicyFixtures = tasks.register(
  "verifySqlShapeSourcePolicyFixtures"
) {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Proves legacy SQL-shape source matching and occurrence counting."
  inputs.property("sqlShapeLegacyPattern", sqlShapeLegacyPattern.pattern)

  doLast {
    val positiveFixtures = linkedMapOf(
      "fixed maxima" to Pair(
        "MAXIMUM_COLUMNS MAXIMUM_JOIN_ROLES MAXIMUM_ARITY",
        3
      ),
      "compound camel null masks" to Pair(
        "nullMask rowNullMask rowNullMasks projectedNullMaskWords",
        4
      ),
      "compound snake null masks" to Pair(
        "null_mask row_null_mask row_null_masks projected_null_mask_words",
        4
      ),
      "qualified and parenthesized shifts" to Pair(
        "1L << row.column; 1L << (state.columnIndex & 63); "
            + "1L << ((plan.lane)); 1L << output.projectionOrdinal",
        4
      ),
      "two occurrences on one line" to Pair(
        "long columns = 1L << column; long projections = 1L << projection;",
        2
      )
    )
    positiveFixtures.forEach { (name, fixture) ->
      val matches = sqlShapeLegacyMatchCount(fixture.first)
      require(matches == fixture.second) {
        "$name fixture produced $matches matches; expected ${fixture.second}"
      }
    }

    val unrelatedFixture = """
      int nullable = 1;
      long mask = 1L << flag;
      long column = 1 << columnIndex;
      long count = 1L << count;
      long bit = 1L << bitIndex;
      long value = otherLong << projection;
      String text = "nullabilityMask";
    """.trimIndent()
    require(sqlShapeLegacyMatchCount(unrelatedFixture) == 0) {
      "unrelated SQL-shape fixture unexpectedly matched"
    }
  }
}

val verifyModuleGraph = tasks.register("verifyModuleGraph") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Rejects River project dependencies outside the approved module DAG."

  doLast {
    val actualGraph = linkedMapOf<String, Set<String>>()
    subprojects.filter { module -> module.name in allowedDependencies }.forEach { module ->
      actualGraph[module.name] = BuildPolicy.inheritedProjectDependencies(
        listOfNotNull(
          module.configurations.getByName("compileClasspath"),
          module.configurations.getByName("runtimeClasspath")
        )
      ) - module.name
    }
    val violations = BuildPolicy.graphViolations(
      actualGraph,
      allowedDependencies
    ).toMutableList()
    val unknownDeclaredModules = declaredDependencies.keys - allowedDependencies.keys
    if (unknownDeclaredModules.isNotEmpty()) {
      violations.add(
        "declared dependency modules are unknown: ${unknownDeclaredModules.sorted()}"
      )
    }
    declaredDependencies.forEach { (module, dependencies) ->
      val allowed = allowedDependencies[module] ?: emptySet()
      val forbidden = dependencies - allowed
      if (forbidden.isNotEmpty()) {
        violations.add(
          "$module declares dependencies outside the maximum graph: ${forbidden.sorted()}"
        )
      }
    }
    actualGraph.forEach { (module, dependencies) ->
      val declared = declaredDependencies.getOrDefault(module, emptySet())
      val undeclared = dependencies - declared
      val stale = declared - dependencies
      if (undeclared.isNotEmpty()) {
        violations.add(
          "$module has undeclared current dependencies: ${undeclared.sorted()}"
        )
      }
      if (stale.isNotEmpty()) {
        violations.add(
          "$module has stale declared dependencies: ${stale.sorted()}"
        )
      }
    }
    val actualApiGraph = linkedMapOf<String, Set<String>>()
    subprojects.filter { module -> module.name in allowedDependencies }.forEach { module ->
      actualApiGraph[module.name] = BuildPolicy.inheritedProjectDependencies(
        listOf(module.configurations.getByName("api"))
      )
    }
    val approvedApiGraph = allowedDependencies.keys.associateWith { module ->
      approvedApiDependencies.getOrDefault(module, emptySet())
    }
    val apiViolations = BuildPolicy.graphViolations(
      actualApiGraph,
      approvedApiGraph
    ).map { violation -> "public API dependency: $violation" }
        .toMutableList()
    val unknownApiModules = approvedApiDependencies.keys - declaredDependencies.keys
    if (unknownApiModules.isNotEmpty()) {
      apiViolations.add(
        "public API dependency modules are not declared: ${unknownApiModules.sorted()}"
      )
    }
    approvedApiDependencies.forEach { (module, dependencies) ->
      val declared = declaredDependencies[module] ?: emptySet()
      val undeclared = dependencies - declared
      if (undeclared.isNotEmpty()) {
        apiViolations.add(
          "$module exports undeclared dependencies: ${undeclared.sorted()}"
        )
      }
    }
    actualApiGraph.forEach { (module, dependencies) ->
      val approved = approvedApiDependencies.getOrDefault(module, emptySet())
      val missing = approved - dependencies
      if (missing.isNotEmpty()) {
        apiViolations.add(
          "$module has stale public API dependencies: ${missing.sorted()}"
        )
      }
    }
    val allViolations = (violations + apiViolations).sorted()
    if (allViolations.isNotEmpty()) {
      throw GradleException(allViolations.joinToString(separator = "\n"))
    }
  }
}

val verifyBuildPolicyFixtures = tasks.register("verifyBuildPolicyFixtures") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Proves each build policy rejects a deterministic negative fixture."

  val fixtureDirectory = layout.buildDirectory.dir("policy-fixtures")
  outputs.dir(fixtureDirectory)

  doLast {
    fun requireViolation(name: String, violations: List<String>, expected: String) {
      if (violations.none { expected in it }) {
        throw GradleException(
          "$name fixture did not produce expected diagnostic '$expected': $violations"
        )
      }
    }

    fun requireNoViolation(name: String, violations: List<String>) {
      if (violations.isNotEmpty()) {
        throw GradleException("$name fixture unexpectedly failed: $violations")
      }
    }

    val root = fixtureDirectory.get().asFile.toPath()
    Files.createDirectories(root)

    fun writeFixture(relative: String, content: String): java.nio.file.Path {
      val path = root.resolve(relative)
      Files.createDirectories(path.parent)
      Files.writeString(path, content)
      return path
    }

    fun sourceViolations(
      sources: List<BuildPolicy.JavaSource>,
      checkedFiles: List<java.nio.file.Path> = sources.map { it.path() },
      hotPackages: Set<String> = emptySet()
    ): List<String> = BuildPolicy.sourceViolations(
      root,
      sources,
      checkedFiles,
      checkedTextExtensions,
      indentedExtensions,
      hotPackages
    )

    val tabPath = writeFixture(
      "tab/Tab.java",
      "package fixture.tab;\n\tfinal class Tab {}\n"
    )
    requireViolation("tab", sourceViolations(emptyList(), listOf(tabPath)), "tab character")

    val indentPath = writeFixture(
      "indent/Indent.java",
      "package fixture.indent;\n final class Indent {}\n"
    )
    requireViolation(
      "indent",
      sourceViolations(emptyList(), listOf(indentPath)),
      "indentation is not a multiple of two"
    )

    val shellTabPath = writeFixture(
      "tab/verify.sh",
      "#!/bin/sh\n\techo rejected\n"
    )
    requireViolation(
      "shell tab",
      sourceViolations(emptyList(), listOf(shellTabPath)),
      "tab character"
    )

    val extensionlessIndentPath = writeFixture(
      "indent/verify",
      "#!/bin/sh\n echo rejected\n"
    )
    requireViolation(
      "extensionless indentation",
      sourceViolations(emptyList(), listOf(extensionlessIndentPath)),
      "indentation is not a multiple of two"
    )

    val ownerPath = writeFixture(
      "internal/owner/Hidden.java",
      "package fixture.owner.internal;\npublic final class Hidden {}\n"
    )
    val consumerPath = writeFixture(
      "internal/consumer/Consumer.java",
      "package fixture.consumer;\n"
          + "import fixture.owner.internal.Hidden;\n"
          + "final class Consumer { Hidden value; }\n"
    )
    val internalSources = listOf(
      BuildPolicy.JavaSource("owner", ownerPath, Files.readString(ownerPath)),
      BuildPolicy.JavaSource("consumer", consumerPath, Files.readString(consumerPath))
    )
    requireViolation(
      "internal package",
      sourceViolations(internalSources),
      "references internal package fixture.owner.internal owned by owner"
    )

    val hotPath = writeFixture(
      "forbidden/HotLoop.java",
      "package fixture.hot;\n"
          + "import java.util.List;\n"
          + "final class HotLoop {\n"
          + "  List<String> values;\n"
          + "  long count() { return values.stream().count(); }\n"
          + "}\n"
    )
    requireViolation(
      "forbidden API",
      sourceViolations(
        listOf(BuildPolicy.JavaSource("hot", hotPath, Files.readString(hotPath))),
        hotPackages = setOf("fixture.hot")
      ),
      "hot-path package references stream/collector APIs"
    )

    val hotPathTest = writeFixture(
      "forbidden/test/HotLoopTest.java",
      "package fixture.hot;\n"
          + "import java.util.List;\n"
          + "final class HotLoopTest {\n"
          + "  List<String> values;\n"
          + "  long count() { return values.parallelStream().count(); }\n"
          + "}\n"
    )
    requireNoViolation(
      "test-source hot-path exclusion",
      sourceViolations(
        listOf(
          BuildPolicy.JavaSource(
            "hot",
            hotPathTest,
            Files.readString(hotPathTest),
            false
          )
        ),
        hotPackages = setOf("fixture.hot")
      )
    )

    val productionTestSupport = writeFixture(
      "forbidden/ProductionTestSupport.java",
      "package fixture.product;\n"
          + "import io.riverdb.testkit.Fixture;\n"
          + "final class ProductionTestSupport {\n"
          + "  void resetForTest(Fixture fixture) {}\n"
          + "}\n"
    )
    val productionTestSupportSource = BuildPolicy.JavaSource(
      "product",
      productionTestSupport,
      Files.readString(productionTestSupport),
      true
    )
    val productionTestViolations = sourceViolations(listOf(productionTestSupportSource))
    requireViolation(
      "production testkit reference",
      productionTestViolations,
      "production source references testkit code"
    )
    requireViolation(
      "production test-support identifier",
      productionTestViolations,
      "production source declares or references a test-support identifier"
    )
    requireNoViolation(
      "test-source test support",
      sourceViolations(listOf(BuildPolicy.JavaSource(
        "product",
        productionTestSupport,
        Files.readString(productionTestSupport),
        false
      )))
    )

    val unicodeBypass = writeFixture(
      "forbidden/UnicodeBypass.java",
      "package fixture.hot;\n"
          + "import java.util.str\\u0065am.IntStream;\n"
          + "final class UnicodeBypass { IntStream values; }\n"
    )
    requireViolation(
      "Unicode escape bypass",
      sourceViolations(
        listOf(
          BuildPolicy.JavaSource(
            "hot",
            unicodeBypass,
            Files.readString(unicodeBypass),
            true
          )
        ),
        hotPackages = setOf("fixture.hot")
      ),
      "raw Java Unicode escape is forbidden"
    )

    requireViolation(
      "inherited custom-configuration dependency",
      BuildPolicy.graphViolations(
        mapOf(
          "fixture-consumer" to BuildPolicy.inheritedProjectDependencies(
            listOf(inheritedClasspathFixture)
          ),
          "river-base" to emptySet()
        ),
        mapOf("fixture-consumer" to emptySet(), "river-base" to emptySet())
      ),
      "fixture-consumer has forbidden dependencies: [river-base]"
    )
    requireViolation(
      "dependency cycle",
      BuildPolicy.graphViolations(
        mapOf("a" to setOf("b"), "b" to setOf("a")),
        mapOf("a" to setOf("b"), "b" to setOf("a"))
      ),
      "module dependency cycle: a -> b -> a"
    )
  }
}

val verifyProjectDependencyVisibility = tasks.register(
  "verifyProjectDependencyVisibility"
) {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Compiles a disposable graph to prove project edges are private by default."

  val fixtureDirectory = layout.buildDirectory.dir(
    "policy-fixtures/project-dependency-visibility"
  )
  outputs.dir(fixtureDirectory)
  outputs.upToDateWhen { false }

  doLast {
    val root = fixtureDirectory.get().asFile
    if (!root.deleteRecursively()) {
      throw GradleException("could not clear dependency visibility fixture $root")
    }
    Files.createDirectories(root.toPath())

    fun writeFixture(relative: String, content: String) {
      val path = root.toPath().resolve(relative)
      Files.createDirectories(path.parent)
      Files.writeString(path, content)
    }

    val catalogStorageConfiguration = if (
      "river-storage" in approvedApiDependencies.getOrDefault(
        "river-catalog",
        emptySet()
      )
    ) {
      "api"
    } else {
      "implementation"
    }
    val sqlCatalogConfiguration = if (
      "river-catalog" in approvedApiDependencies.getOrDefault(
        "river-sql",
        emptySet()
      )
    ) {
      "api"
    } else {
      "implementation"
    }

    writeFixture(
      "settings.gradle.kts",
      """
      rootProject.name = "project-dependency-visibility"
      include(
        "river-storage",
        "river-catalog",
        "river-sql",
        "approved-direct-consumer"
      )
      """.trimIndent() + "\n"
    )
    writeFixture(
      "build.gradle.kts",
      """
      import org.gradle.api.tasks.compile.JavaCompile

      subprojects {
        apply(plugin = "java-library")

        extensions.configure<JavaPluginExtension> {
          toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
          }
        }

        tasks.withType<JavaCompile>().configureEach {
          options.release.set(25)
          options.encoding = "UTF-8"
          options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
        }
      }

      project(":river-catalog") {
        dependencies.add(
          "$catalogStorageConfiguration",
          dependencies.project(":river-storage")
        )
      }
      project(":river-sql") {
        dependencies.add(
          "$sqlCatalogConfiguration",
          dependencies.project(":river-catalog")
        )
      }
      project(":approved-direct-consumer") {
        dependencies.add(
          "implementation",
          dependencies.project(":river-storage")
        )
      }
      """.trimIndent() + "\n"
    )
    writeFixture(
      "river-storage/src/main/java/io/riverdb/fixture/storage/StorageType.java",
      """
      package io.riverdb.fixture.storage;

      public final class StorageType {
        private StorageType() {
        }

        public static long identity() {
          return 7L;
        }
      }
      """.trimIndent() + "\n"
    )
    writeFixture(
      "river-catalog/src/main/java/io/riverdb/fixture/catalog/CatalogType.java",
      """
      package io.riverdb.fixture.catalog;

      public final class CatalogType {
        private CatalogType() {
        }

        public static long identity() {
          return 11L;
        }
      }
      """.trimIndent() + "\n"
    )
    val storageConsumer = """
      package io.riverdb.fixture.consumer;

      import io.riverdb.fixture.storage.StorageType;

      public final class StorageConsumer {
        private StorageConsumer() {
        }

        public static long identity() {
          return StorageType.identity();
        }
      }
    """.trimIndent() + "\n"
    writeFixture(
      "river-sql/src/main/java/io/riverdb/fixture/consumer/StorageConsumer.java",
      storageConsumer
    )
    writeFixture(
      "approved-direct-consumer/src/main/java/io/riverdb/fixture/consumer/StorageConsumer.java",
      storageConsumer
    )

    data class FixtureResult(val exitCode: Int, val output: String)

    fun runFixture(task: String): FixtureResult {
      val gradleHome = gradle.gradleHomeDir
          ?: throw GradleException("Gradle installation directory is unavailable")
      val gradleExecutable = gradleHome.resolve("bin/gradle")
      val process = ProcessBuilder(
        gradleExecutable.absolutePath,
        "--offline",
        "--no-daemon",
        "--console=plain",
        task
      )
          .directory(root)
          .redirectErrorStream(true)
          .apply {
            environment()["GRADLE_USER_HOME"] = gradle.gradleUserHomeDir.absolutePath
          }
          .start()
      val output = process.inputStream.bufferedReader().use { it.readText() }
      return FixtureResult(process.waitFor(), output)
    }

    val direct = runFixture(":approved-direct-consumer:compileJava")
    if (direct.exitCode != 0) {
      throw GradleException(
        "approved direct project dependency did not compile:\n${direct.output}"
      )
    }

    val transitive = runFixture(":river-sql:compileJava")
    if (transitive.exitCode == 0) {
      throw GradleException(
        "river-sql fixture compiled against river-storage solely through river-catalog"
      )
    }
    if (
      "StorageType" !in transitive.output
          || (
            "does not exist" !in transitive.output
                && "cannot find symbol" !in transitive.output
          )
    ) {
      throw GradleException(
        "transitive compilation failed for an unexpected reason:\n${transitive.output}"
      )
    }
  }
}

fun classFilesUnder(directories: Collection<java.io.File>): List<java.nio.file.Path> {
  val files = mutableListOf<java.nio.file.Path>()
  directories.sorted().forEach { directory ->
    if (!directory.isDirectory) {
      return@forEach
    }
    Files.walk(directory.toPath()).use { paths ->
      paths.filter { path ->
        Files.isRegularFile(path) && path.fileName.toString().endsWith(".class")
      }.forEach(files::add)
    }
  }
  return files.sorted()
}

val productionClassDirectories = subprojects.map { module ->
  module.layout.buildDirectory.dir("classes/java/main")
}
val productionHierarchyManifests = subprojects.map { module ->
  val manifest = module.layout.buildDirectory.file(
    "reports/hot-path-bytecode-runtime-classpath.txt"
  )
  val writeManifest = module.tasks.register("writeHotPathBytecodeHierarchyManifest") {
    val runtimeClasspath = module.configurations.named("runtimeClasspath")
    inputs.files(runtimeClasspath)
    outputs.file(manifest)

    doLast {
      val lines = runtimeClasspath.get().files
          .map { file -> file.toPath().toAbsolutePath().normalize().toString() }
          .distinct()
          .sorted()
      val path = manifest.get().asFile.toPath()
      Files.createDirectories(path.parent)
      Files.write(path, lines)
    }
  }
  writeManifest to manifest
}
val verifyHotPathBytecode = tasks.register("verifyHotPathBytecode") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Audits exact declared production hot methods in structured class files."
  dependsOn(subprojects.map { module -> module.tasks.named("compileJava") })
  dependsOn(productionHierarchyManifests.map { (task, _) -> task })
  inputs.files(productionClassDirectories)
  inputs.files(productionHierarchyManifests.map { (_, manifest) -> manifest })

  doLast {
    val classFiles = classFilesUnder(
      productionClassDirectories.map { directory -> directory.get().asFile }
    )
    val hierarchyEntries = productionHierarchyManifests
        .flatMap { (_, manifest) -> Files.readAllLines(manifest.get().asFile.toPath()) }
        .map { entry -> java.nio.file.Path.of(entry) }
        .distinct()
    val violations = HotPathBytecodePolicy.violations(
      rootDir.toPath(),
      classFiles,
      hierarchyEntries,
      liveHotPathMethods,
      liveHotPathAllowedRules
    )
    if (violations.isNotEmpty()) {
      throw GradleException(violations.joinToString(separator = "\n"))
    }
  }
}

val indexedTableReferenceRules = linkedMapOf(
  "io/riverdb/engine/table/IndexedTable" to setOf(
    "io/riverdb/engine/table/IndexedTableKernel"
  ),
  "io/riverdb/engine/table/IndexedTableKernel" to setOf(
    "io/riverdb/engine/table/IndexedTableStore",
    "io/riverdb/engine/table/IndexedTable"
  ),
  "io/riverdb/engine/table/IndexedTableStore" to setOf(
    "io/riverdb/engine/table/IndexedTable"
  )
)
val verifyIndexedTableClassReferences = tasks.register(
  "verifyIndexedTableClassReferences"
) {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Enforces the compiled one-way IndexedTable -> Store -> Kernel graph."
  dependsOn(project(":river-engine").tasks.named("compileJava"))
  val engineClasses = project(":river-engine").layout.buildDirectory.dir("classes/java/main")
  inputs.files(engineClasses)

  doLast {
    val violations = ClassReferencePolicy.violations(
      rootDir.toPath(),
      classFilesUnder(listOf(engineClasses.get().asFile)),
      indexedTableReferenceRules
    )
    if (violations.isNotEmpty()) {
      throw GradleException(violations.joinToString(separator = "\n"))
    }
  }
}

val classReferenceFixtureSources = fileTree(
  rootDir.resolve("buildSrc/src/test/resources/class-reference-policy")
) {
  include("*.java")
}
val classReferenceFixtureClasses =
  layout.buildDirectory.dir("class-reference-policy-fixtures/classes")
val compileClassReferencePolicyFixtures = tasks.register(
  "compileClassReferencePolicyFixtures"
) {
  inputs.files(classReferenceFixtureSources)
  outputs.dir(classReferenceFixtureClasses)

  doLast {
    val output = classReferenceFixtureClasses.get().asFile
    if (!output.deleteRecursively()) {
      throw GradleException("could not clear scoped class-reference fixture output $output")
    }
    Files.createDirectories(output.toPath())
    val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        ?: throw GradleException("a Java 25 JDK compiler is required for policy fixtures")
    val arguments = mutableListOf(
      "--release", "25",
      "-Xlint:none",
      "-d", output.absolutePath
    )
    arguments.addAll(classReferenceFixtureSources.files.map { it.absolutePath }.sorted())
    val exitCode = compiler.run(null, null, null, *arguments.toTypedArray())
    if (exitCode != 0) {
      throw GradleException("class-reference policy fixtures failed to compile: exit $exitCode")
    }
  }
}

val verifyClassReferencePolicyFixtures = tasks.register(
  "verifyClassReferencePolicyFixtures"
) {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Proves every indexed-table forbidden reference edge with compiled fixtures."
  dependsOn(compileClassReferencePolicyFixtures)
  inputs.files(classReferenceFixtureClasses)

  doLast {
    val fixtureRules = linkedMapOf(
      "fixture/reference/Table" to setOf("fixture/reference/Kernel"),
      "fixture/reference/Kernel" to setOf(
        "fixture/reference/Store",
        "fixture/reference/Table"
      ),
      "fixture/reference/Store" to setOf("fixture/reference/Table")
    )
    val violations = ClassReferencePolicy.violations(
      rootDir.toPath(),
      classFilesUnder(listOf(classReferenceFixtureClasses.get().asFile)),
      fixtureRules
    )
    val expectedEdges = setOf(
      "fixture/reference/Table -> fixture/reference/Kernel",
      "fixture/reference/Table\$Nested -> fixture/reference/Kernel",
      "fixture/reference/Table\$DescriptorOnly -> fixture/reference/Kernel",
      "fixture/reference/Table\$TypeOperations -> fixture/reference/Kernel",
      "fixture/reference/Kernel -> fixture/reference/Store",
      "fixture/reference/Kernel -> fixture/reference/Table",
      "fixture/reference/Store -> fixture/reference/Table"
    )
    expectedEdges.forEach { edge ->
      require(violations.any { edge in it }) {
        "class-reference fixture did not prove forbidden edge $edge"
      }
    }
    require(violations.size == expectedEdges.size) {
      "class-reference fixtures produced unexpected violations: $violations"
    }
  }
}

val sqlRuntimeInvocationRules = linkedMapOf(
  "io/riverdb/engine/sql/SqlNestedQueryExecution" to setOf(
    InvocationPolicy.Invocation(
      "io/riverdb/engine/relational/RelationalSession",
      "resolveTable",
      "(Ljava/lang/CharSequence;Lio/riverdb/engine/relational/TableDefinition;)"
          + "Lio/riverdb/base/error/StatusCode;"
    ),
    InvocationPolicy.Invocation(
      "io/riverdb/engine/relational/TableDefinition",
      "findColumn",
      "(Ljava/lang/CharSequence;)I"
    )
  ),
  "io/riverdb/engine/sql/SqlQueryExecution" to setOf(
    InvocationPolicy.Invocation(
      "io/riverdb/engine/relational/TableDefinition",
      "findColumn",
      "(Ljava/lang/CharSequence;)I"
    )
  )
)
val verifySqlRuntimeInvocationPolicy = tasks.register(
  "verifySqlRuntimeInvocationPolicy"
) {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Forbids runtime table and column resolution in SQL execution."
  dependsOn(project(":river-engine").tasks.named("compileJava"))
  val engineClasses = project(":river-engine").layout.buildDirectory.dir("classes/java/main")
  inputs.files(engineClasses)

  doLast {
    val violations = InvocationPolicy.violations(
      rootDir.toPath(),
      classFilesUnder(listOf(engineClasses.get().asFile)),
      sqlRuntimeInvocationRules
    )
    if (violations.isNotEmpty()) {
      throw GradleException(violations.joinToString(separator = "\n"))
    }
  }
}

val invocationPolicyFixtureSources = fileTree(
  rootDir.resolve("buildSrc/src/test/resources/invocation-policy")
) {
  include("*.java")
}
val invocationPolicyFixtureClasses =
  layout.buildDirectory.dir("invocation-policy-fixtures/classes")
val compileInvocationPolicyFixtures = tasks.register(
  "compileInvocationPolicyFixtures"
) {
  inputs.files(invocationPolicyFixtureSources)
  outputs.dir(invocationPolicyFixtureClasses)

  doLast {
    val output = invocationPolicyFixtureClasses.get().asFile
    if (!output.deleteRecursively()) {
      throw GradleException("could not clear scoped invocation-policy fixture output $output")
    }
    Files.createDirectories(output.toPath())
    val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        ?: throw GradleException("a Java 25 JDK compiler is required for policy fixtures")
    val arguments = mutableListOf(
      "--release", "25",
      "-Xlint:none",
      "-d", output.absolutePath
    )
    arguments.addAll(invocationPolicyFixtureSources.files.map { it.absolutePath }.sorted())
    val exitCode = compiler.run(null, null, null, *arguments.toTypedArray())
    if (exitCode != 0) {
      throw GradleException("invocation policy fixtures failed to compile: exit $exitCode")
    }
  }
}

val verifyInvocationPolicyFixtures = tasks.register(
  "verifyInvocationPolicyFixtures"
) {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Proves exact forbidden invocation matching with compiled fixtures."
  dependsOn(compileInvocationPolicyFixtures)
  inputs.files(invocationPolicyFixtureClasses)

  doLast {
    val fixtureRules = linkedMapOf(
      "fixture/invocation/NegativeInvocations" to setOf(
        InvocationPolicy.Invocation(
          "fixture/invocation/RelationalSession",
          "resolveTable",
          "(Ljava/lang/CharSequence;Lfixture/invocation/TableDefinition;)I"
        ),
        InvocationPolicy.Invocation(
          "fixture/invocation/TableDefinition",
          "findColumn",
          "(Ljava/lang/CharSequence;)I"
        )
      ),
      "fixture/invocation/QueryExecution" to setOf(
        InvocationPolicy.Invocation(
          "fixture/invocation/TableDefinition",
          "findColumn",
          "(Ljava/lang/CharSequence;)I"
        )
      )
    )
    val violations = InvocationPolicy.violations(
      rootDir.toPath(),
      classFilesUnder(listOf(invocationPolicyFixtureClasses.get().asFile)),
      fixtureRules
    )
    val expectedInvocations = setOf(
      "fixture/invocation/NegativeInvocations -> "
          + "fixture/invocation/RelationalSession.resolveTable"
          + "(Ljava/lang/CharSequence;Lfixture/invocation/TableDefinition;)I",
      "fixture/invocation/NegativeInvocations\$Nested -> "
          + "fixture/invocation/TableDefinition.findColumn(Ljava/lang/CharSequence;)I",
      "fixture/invocation/QueryExecution -> "
          + "fixture/invocation/TableDefinition.findColumn(Ljava/lang/CharSequence;)I",
      "fixture/invocation/QueryExecution\$Nested -> "
          + "fixture/invocation/TableDefinition.findColumn(Ljava/lang/CharSequence;)I"
    )
    expectedInvocations.forEach { invocation ->
      require(violations.any { invocation in it }) {
        "invocation fixture did not prove forbidden call $invocation"
      }
    }
    require(violations.size == expectedInvocations.size) {
      "invocation fixtures produced unexpected violations: $violations"
    }
  }
}

val hotPathFixtureSources = fileTree(
  rootDir.resolve("buildSrc/src/test/resources/bytecode-policy")
) {
  include("*.java")
}
val hotPathFixtureClasses = layout.buildDirectory.dir("bytecode-policy-fixtures/classes")
val compileHotPathBytecodeFixtures = tasks.register("compileHotPathBytecodeFixtures") {
  inputs.files(hotPathFixtureSources)
  outputs.dir(hotPathFixtureClasses)

  doLast {
    val output = hotPathFixtureClasses.get().asFile
    Files.createDirectories(output.toPath())
    val staleMarker = output.resolve("stale-output.marker")
    Files.writeString(staleMarker.toPath(), "must be removed before compilation\n")
    if (!output.deleteRecursively()) {
      throw GradleException("could not clear scoped bytecode fixture output $output")
    }
    Files.createDirectories(output.toPath())
    val compiler = javax.tools.ToolProvider.getSystemJavaCompiler()
        ?: throw GradleException("a Java 25 JDK compiler is required for policy fixtures")
    val arguments = mutableListOf(
      "--release", "25",
      "-Xlint:none",
      "-d", output.absolutePath
    )
    arguments.addAll(hotPathFixtureSources.files.map { it.absolutePath }.sorted())
    val exitCode = compiler.run(null, null, null, *arguments.toTypedArray())
    if (exitCode != 0) {
      throw GradleException("hot-path bytecode fixtures failed to compile: exit $exitCode")
    }
    if (staleMarker.exists()) {
      throw GradleException("stale bytecode fixture output survived compilation")
    }
  }
}

val verifyHotPathBytecodeFixtures = tasks.register("verifyHotPathBytecodeFixtures") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Proves every hot-path bytecode rule with compiled negative fixtures."
  dependsOn(compileHotPathBytecodeFixtures)
  inputs.files(hotPathFixtureClasses)

  doLast {
    val fixtureClassFiles = classFilesUnder(listOf(hotPathFixtureClasses.get().asFile))
    val negativeClass = "fixture.bytecode.NegativeHotPath"
    val negativeRules = linkedMapOf(
      hotMethod(negativeClass, "boxingValueOf", "(I)Ljava/lang/Integer;") to "HP003",
      hotMethod(negativeClass, "boxingConstructor", "(I)Ljava/lang/Integer;") to "HP003",
      hotMethod(negativeClass, "stream", "(Ljava/util/List;)J") to "HP004",
      hotMethod(
        negativeClass,
        "collector",
        "(Ljava/util/List;)Ljava/util/List;"
      ) to "HP004",
      hotMethod(negativeClass, "format", "(I)Ljava/lang/String;") to "HP005",
      hotMethod(negativeClass, "formatter", "(I)Ljava/lang/String;") to "HP005",
      hotMethod(
        negativeClass,
        "exceptionConstruction",
        "()Ljava/lang/RuntimeException;"
      ) to "HP006",
      hotMethod(negativeClass, "exceptionThrow", "()V") to "HP007",
      hotMethod(negativeClass, "objectAllocation", "()Ljava/lang/Object;") to "HP001",
      hotMethod(negativeClass, "arrayAllocation", "(I)[I") to "HP002",
      hotMethod(negativeClass, "concat", "(I)Ljava/lang/String;") to "HP008",
      hotMethod(
        negativeClass,
        "capturedLambda",
        "(I)Ljava/util/function/IntSupplier;"
      ) to "HP010",
      hotMethod(negativeClass, "varargs", "(I)V") to "HP002"
    )
    val negativeViolations = HotPathBytecodePolicy.violations(
      rootDir.toPath(),
      fixtureClassFiles,
      negativeRules.keys,
      emptyMap()
    )
    negativeRules.forEach { (scope, rule) ->
      val methodMarker = "${scope.className().replace('/', '.')}#"
          .plus(scope.methodName())
          .plus(scope.descriptor())
      if (negativeViolations.none { violation ->
          methodMarker in violation && rule in violation
        }) {
        throw GradleException(
          "bytecode fixture $methodMarker did not produce $rule: $negativeViolations"
        )
      }
    }

    val positiveClass = "fixture.bytecode.PositiveHotPath"
    val positiveScopes = setOf(
      hotMethod(positiveClass, "publish", "([JIJJ)I"),
      hotMethod(positiveClass, "copy", "([B[BI)I")
    )
    val positiveViolations = HotPathBytecodePolicy.violations(
      rootDir.toPath(),
      fixtureClassFiles,
      positiveScopes,
      emptyMap()
    )
    if (positiveViolations.isNotEmpty()) {
      throw GradleException(
        "caller-owned/status bytecode fixtures unexpectedly failed: $positiveViolations"
      )
    }

    val objectAllocationScope = hotMethod(
      negativeClass,
      "objectAllocation",
      "()Ljava/lang/Object;"
    )
    val exactAllowanceViolations = HotPathBytecodePolicy.violations(
      rootDir.toPath(),
      fixtureClassFiles,
      setOf(objectAllocationScope),
      mapOf(
        objectAllocationScope to setOf(
          HotPathBytecodePolicy.Allowance(
            HotPathBytecodePolicy.Rule.OBJECT_ALLOCATION,
            "new java.lang.Object"
          )
        )
      )
    )
    if (exactAllowanceViolations.isNotEmpty()) {
      throw GradleException(
        "exact bytecode allowance unexpectedly failed: $exactAllowanceViolations"
      )
    }

    val positivePublish = positiveScopes.first { scope ->
      scope.methodName() == "publish"
    }
    val staleAllowanceViolations = HotPathBytecodePolicy.violations(
      rootDir.toPath(),
      fixtureClassFiles,
      setOf(positivePublish),
      mapOf(
        positivePublish to setOf(
          HotPathBytecodePolicy.Allowance(
            HotPathBytecodePolicy.Rule.OBJECT_ALLOCATION,
            "new java.lang.Object"
          )
        )
      )
    )
    if (staleAllowanceViolations.none { violation ->
        "stale hot-path allowlist" in violation
      }) {
      throw GradleException(
        "stale bytecode allowance fixture did not fail: $staleAllowanceViolations"
      )
    }

    val missingScopeViolations = HotPathBytecodePolicy.violations(
      rootDir.toPath(),
      fixtureClassFiles,
      setOf(hotMethod(positiveClass, "publish", "()V")),
      emptyMap()
    )
    if (missingScopeViolations.none { violation ->
        "missing hot-path method" in violation
      }) {
      throw GradleException(
        "missing bytecode selector fixture did not fail: $missingScopeViolations"
      )
    }

    val adversarialClass = "fixture.bytecode.AdversarialHotPath"
    val threadDeathScope = hotMethod(
      adversarialClass,
      "threadDeath",
      "()Ljava/lang/ThreadDeath;"
    )
    val threadDeathViolations = HotPathBytecodePolicy.violations(
      rootDir.toPath(),
      fixtureClassFiles,
      setOf(threadDeathScope),
      emptyMap()
    )
    if (threadDeathViolations.none { "HP001" in it }
        || threadDeathViolations.none { "HP006" in it }) {
      throw GradleException(
        "ThreadDeath ancestry fixture did not detect allocation and Throwable: "
            + threadDeathViolations
      )
    }

    val threadDeathAllowanceViolations = HotPathBytecodePolicy.violations(
      rootDir.toPath(),
      fixtureClassFiles,
      setOf(threadDeathScope),
      mapOf(
        threadDeathScope to setOf(
          HotPathBytecodePolicy.Allowance(
            HotPathBytecodePolicy.Rule.OBJECT_ALLOCATION,
            "new java.lang.ThreadDeath"
          )
        )
      )
    )
    if (threadDeathAllowanceViolations.none { "HP006" in it }
        || threadDeathAllowanceViolations.any { "HP001" in it }) {
      throw GradleException(
        "object allowance masked Throwable construction: $threadDeathAllowanceViolations"
      )
    }

    val misleadingExceptionViolations = HotPathBytecodePolicy.violations(
      rootDir.toPath(),
      fixtureClassFiles,
      setOf(
        hotMethod(
          adversarialClass,
          "misleadingException",
          "()Lfixture/bytecode/AdversarialHotPath\$MisleadingException;"
        )
      ),
      emptyMap()
    )
    if (misleadingExceptionViolations.none { "HP001" in it }
        || misleadingExceptionViolations.any { "HP006" in it }) {
      throw GradleException(
        "misleading exception name fixture was misclassified: $misleadingExceptionViolations"
      )
    }

    val customStreamViolations = HotPathBytecodePolicy.violations(
      rootDir.toPath(),
      fixtureClassFiles,
      setOf(
        hotMethod(
          adversarialClass,
          "customStream",
          "(Lfixture/bytecode/AdversarialHotPath\$CustomStream;)I"
        )
      ),
      emptyMap()
    )
    if (customStreamViolations.isNotEmpty()) {
      throw GradleException(
        "custom non-stream method name was misclassified: $customStreamViolations"
      )
    }

    val fixtureWithoutExternalBase = fixtureClassFiles.filterNot { path ->
      path.toString().endsWith("fixture/external/ExternalThrowableBase.class")
    }
    val fixtureHierarchy = listOf(hotPathFixtureClasses.get().asFile.toPath())
    val externalThrowableScope = hotMethod(
      adversarialClass,
      "externalThrowable",
      "()Lfixture/bytecode/AdversarialHotPath\$ExternalThrowable;"
    )
    val externalThrowableViolations = HotPathBytecodePolicy.violations(
      rootDir.toPath(),
      fixtureWithoutExternalBase,
      fixtureHierarchy,
      setOf(externalThrowableScope),
      emptyMap()
    )
    if (externalThrowableViolations.none { "HP006" in it }
        || externalThrowableViolations.any { "unresolved throwable ancestry" in it }) {
      throw GradleException(
        "controlled external Throwable hierarchy was not resolved: "
            + externalThrowableViolations
      )
    }

    val unknownThrowableViolations = HotPathBytecodePolicy.violations(
      rootDir.toPath(),
      fixtureWithoutExternalBase,
      setOf(
        hotMethod(
          adversarialClass,
          "unknownThrowable",
          "()Lfixture/bytecode/AdversarialHotPath\$UnknownThrowable;"
        )
      ),
      emptyMap()
    )
    if (unknownThrowableViolations.none { violation ->
        "HP006" in violation && "unresolved throwable ancestry" in violation
      }) {
      throw GradleException(
        "unknown Throwable hierarchy did not fail closed: $unknownThrowableViolations"
      )
    }

    val duplicateAllocationScope = hotMethod(
      adversarialClass,
      "duplicateAllocation",
      "(Z)Ljava/lang/Object;"
    )
    val duplicateAllowanceViolations = HotPathBytecodePolicy.violations(
      rootDir.toPath(),
      fixtureClassFiles,
      setOf(duplicateAllocationScope),
      mapOf(
        duplicateAllocationScope to setOf(
          HotPathBytecodePolicy.Allowance(
            HotPathBytecodePolicy.Rule.OBJECT_ALLOCATION,
            "new java.lang.Object"
          )
        )
      )
    )
    if (duplicateAllowanceViolations.count { "HP001" in it } != 1) {
      throw GradleException(
        "single-use allowance did not leave one duplicate violation: "
            + duplicateAllowanceViolations
      )
    }

    val adversarialPath = hotPathFixtureClasses.get().asFile.toPath()
        .resolve("fixture/bytecode/AdversarialHotPath.class")
    val adversarialBytes = Files.readAllBytes(adversarialPath)
    val malformedDirectory = layout.buildDirectory
        .dir("bytecode-policy-fixtures/malformed")
        .get().asFile
    if (malformedDirectory.exists() && !malformedDirectory.deleteRecursively()) {
      throw GradleException("could not clear malformed bytecode fixtures")
    }
    Files.createDirectories(malformedDirectory.toPath())

    fun requireMalformed(name: String, bytes: ByteArray) {
      val path = malformedDirectory.toPath().resolve("$name.class")
      Files.write(path, bytes)
      val structuralViolations = HotPathBytecodePolicy.violations(
        rootDir.toPath(),
        listOf(path),
        emptySet(),
        emptyMap()
      )
      if (structuralViolations.none { violation ->
          "malformed class file" in violation
              || "invalid class file" in violation
              || "expected Java 25 class version" in violation
        }) {
        throw GradleException(
          "malformed $name fixture was accepted: $structuralViolations"
        )
      }
    }

    requireMalformed(
      "invalid-version",
      HotPathBytecodeFixtureMutator.invalidVersion(adversarialBytes)
    )
    requireMalformed(
      "invalid-constant-pool-tag",
      HotPathBytecodeFixtureMutator.invalidConstantPoolTag(adversarialBytes)
    )
    requireMalformed(
      "truncated",
      HotPathBytecodeFixtureMutator.truncated(adversarialBytes)
    )
    requireMalformed(
      "reserved-opcode",
      HotPathBytecodeFixtureMutator.invalidReservedOpcode(
        adversarialBytes,
        "reservedVictim"
      )
    )
    requireMalformed(
      "invalid-wide",
      HotPathBytecodeFixtureMutator.invalidWide(adversarialBytes, "wideVictim")
    )
    requireMalformed(
      "invokeinterface-reserved",
      HotPathBytecodeFixtureMutator.invalidInvokeInterfaceReserved(
        adversarialBytes,
        "interfaceInvoke"
      )
    )
    requireMalformed(
      "invokedynamic-reserved",
      HotPathBytecodeFixtureMutator.invalidInvokeDynamicReserved(
        adversarialBytes,
        "dynamicConcat"
      )
    )
    requireMalformed(
      "newarray-type",
      HotPathBytecodeFixtureMutator.invalidNewArrayType(
        adversarialBytes,
        "primitiveArray"
      )
    )
    requireMalformed(
      "multianewarray-dimensions",
      HotPathBytecodeFixtureMutator.invalidMultiArrayDimensions(
        adversarialBytes,
        "multiArray"
      )
    )
    requireMalformed(
      "tableswitch-bounds",
      HotPathBytecodeFixtureMutator.invalidTableSwitchBounds(
        adversarialBytes,
        "tableSwitch"
      )
    )
    requireMalformed(
      "tableswitch-target",
      HotPathBytecodeFixtureMutator.invalidTableSwitchTarget(
        adversarialBytes,
        "tableSwitch"
      )
    )
    requireMalformed(
      "lookupswitch-order",
      HotPathBytecodeFixtureMutator.invalidLookupSwitchOrder(
        adversarialBytes,
        "lookupSwitch"
      )
    )
    requireMalformed(
      "code-attribute-length",
      HotPathBytecodeFixtureMutator.invalidCodeAttributeLength(
        adversarialBytes,
        "reservedVictim"
      )
    )
    requireMalformed(
      "duplicate-code-attribute",
      HotPathBytecodeFixtureMutator.duplicateCodeAttribute(
        adversarialBytes,
        "reservedVictim"
      )
    )
    requireMalformed(
      "bootstrap-reference",
      HotPathBytecodeFixtureMutator.invalidBootstrapReference(adversarialBytes)
    )

    val hierarchySemanticPath = malformedDirectory.toPath()
        .resolve("hierarchy-before-semantic-error.class")
    Files.write(
      hierarchySemanticPath,
      HotPathBytecodeFixtureMutator.invalidReferenceReturn(
        adversarialBytes,
        "externalThrowable"
      )
    )
    val hierarchySemanticViolations = HotPathBytecodePolicy.violations(
      rootDir.toPath(),
      listOf(hierarchySemanticPath),
      fixtureHierarchy,
      setOf(externalThrowableScope),
      emptyMap()
    )
    if (hierarchySemanticViolations.none { violation ->
        "invalid class file" in violation && "Could not resolve class" !in violation
      }) {
      throw GradleException(
        "resolved hierarchy did not expose later verifier error: "
            + hierarchySemanticViolations
      )
    }

    val misleadingInvokeDynamicPath = malformedDirectory.toPath()
        .resolve("misleading-invokedynamic-name.class")
    Files.write(
      misleadingInvokeDynamicPath,
      HotPathBytecodeFixtureMutator.misleadingInvokeDynamicName(adversarialBytes)
    )
    val misleadingInvokeDynamicViolations = HotPathBytecodePolicy.violations(
      rootDir.toPath(),
      listOf(misleadingInvokeDynamicPath),
      setOf(
        hotMethod(
          adversarialClass,
          "dynamicConcat",
          "(I)Ljava/lang/String;"
        )
      ),
      emptyMap()
    )
    if (misleadingInvokeDynamicViolations.none { "HP008" in it }
        || misleadingInvokeDynamicViolations.any { "HP010" in it }) {
      throw GradleException(
        "bootstrap-owner classification fixture failed: "
            + misleadingInvokeDynamicViolations
      )
    }
  }
}

val expectedArchiveList = layout.buildDirectory.file("reports/expected-archives.paths")
val expectedArchiveCount = layout.buildDirectory.file("reports/expected-archives.count")
val writeExpectedArchiveList = tasks.register("writeExpectedArchiveList") {
  outputs.files(expectedArchiveList, expectedArchiveCount)

  doLast {
    val archivePaths = subprojects.flatMap { module ->
      listOf(
        module.tasks.named<Jar>("jar").get().archiveFile.get().asFile,
        module.tasks.named<Jar>("sourcesJar").get().archiveFile.get().asFile
      )
    }.map { archive ->
      rootDir.toPath().toAbsolutePath().normalize()
        .relativize(archive.toPath().toAbsolutePath().normalize())
        .toString()
        .replace(java.io.File.separatorChar, '/')
    }.sorted()
    val listPath = expectedArchiveList.get().asFile.toPath()
    val countPath = expectedArchiveCount.get().asFile.toPath()
    Files.createDirectories(listPath.parent)
    Files.write(listPath, archivePaths)
    Files.writeString(countPath, "${archivePaths.size}\n")
  }
}

tasks.register("assembleRiverArchives") {
  group = LifecycleBasePlugin.BUILD_GROUP
  description = "Assembles every production and benchmark JAR for comparison."
  dependsOn(writeExpectedArchiveList)
  dependsOn(subprojects.flatMap { module ->
    listOf(module.tasks.named("jar"), module.tasks.named("sourcesJar"))
  })
}

fun sha256(file: java.io.File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().use { input ->
    val buffer = ByteArray(16 * 1024)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) {
        break
      }
      digest.update(buffer, 0, read)
    }
  }
  return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

val externalDependencyReports = subprojects.associateWith { module ->
  val report = module.layout.buildDirectory.file("reports/external-dependencies.tsv")
  module.tasks.register("writeExternalDependencyReport") {
    outputs.file(report)
    outputs.upToDateWhen { false }

    doLast {
      val resolved = sortedMapOf<String, String>()
      module.configurations.filter { it.isCanBeResolved }.forEach { configuration ->
        val recognizedFiles = mutableSetOf<java.nio.file.Path>()
        configuration.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
          recognizedFiles.add(artifact.file.toPath().toAbsolutePath().normalize())
          val component = artifact.moduleVersion.id
          val identifier = artifact.id.componentIdentifier
          if (identifier is ModuleComponentIdentifier) {
            require(artifact.extension == "jar" && artifact.classifier.isNullOrBlank()) {
              "external artifact classifiers/extensions are unsupported by ledger v1: " +
                  "${component.group}:${component.name}:${component.version}:" +
                  "${artifact.classifier}:${artifact.extension}"
            }
            val key = "${component.group}:${component.name}:${component.version}"
            val checksum = sha256(artifact.file)
            val previous = resolved.putIfAbsent(key, checksum)
            require(previous == null || previous == checksum) {
              "external dependency $key resolved to different bytes in ${module.path}"
            }
          } else {
            require(identifier is ProjectComponentIdentifier) {
              "unsupported non-module dependency in ${module.path}:${configuration.name}: " +
                  identifier.displayName
            }
          }
        }
        val untrackedFiles = configuration.resolve()
            .map { it.toPath().toAbsolutePath().normalize() }
            .filterNot { recognizedFiles.contains(it) }
            .sorted()
        require(untrackedFiles.isEmpty()) {
          "file/self-resolving dependencies are unsupported by ledger v1 in " +
              "${module.path}:${configuration.name}: $untrackedFiles"
        }
      }
      val reportPath = report.get().asFile.toPath()
      Files.createDirectories(reportPath.parent)
      Files.write(
        reportPath,
        resolved.map { (coordinate, checksum) -> "$coordinate\t$checksum" }
      )
    }
  }
}

val verifyDependencyLedger = tasks.register("verifyDependencyLedger") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Verifies the full ledger and every resolved external artifact identity."
  dependsOn(externalDependencyReports.values)

  doLast {
    val ledgerPath = rootDir.toPath().resolve("docs/governance/provenance-ledger.csv")
    val artifactRows = ProvenancePolicy.read(ledgerPath)
    ProvenancePolicy.verifyRepositoryNotices(rootDir.toPath(), artifactRows.values)

    val wrapperJarRow = artifactRows.getValue("gradle-wrapper-jar")
    val wrapperJarChecksum = sha256(rootDir.resolve("gradle/wrapper/gradle-wrapper.jar"))
    require(wrapperJarRow.sha256() == wrapperJarChecksum) {
      "Gradle wrapper JAR checksum does not match the provenance ledger"
    }
    val wrapperProperties = java.util.Properties()
    rootDir.resolve("gradle/wrapper/gradle-wrapper.properties").inputStream().use {
      wrapperProperties.load(it)
    }
    val distributionRow = artifactRows.getValue("gradle-distribution")
    require(
      distributionRow.sha256() == wrapperProperties.getProperty("distributionSha256Sum")
    ) {
      "Gradle distribution checksum does not match wrapper properties"
    }

    val resolved = sortedMapOf<String, String>()
    externalDependencyReports.values.forEach { reportTask ->
      val reportPath = reportTask.get().outputs.files.singleFile.toPath()
      Files.readAllLines(reportPath).forEach { line ->
        val fields = line.split('\t')
        require(fields.size == 2) { "invalid external dependency report line: $line" }
        val previous = resolved.putIfAbsent(fields[0], fields[1])
        require(previous == null || previous == fields[1]) {
          "external dependency ${fields[0]} resolved to multiple checksums"
        }
      }
    }

    ProvenancePolicy.verifyResolvedDependencies(artifactRows.values, resolved)
    ProvenancePolicy.verifyGradleMetadata(
      rootDir.toPath().resolve("gradle/verification-metadata.xml"),
      resolved
    )
  }
}

val verifyReferenceSnapshots = tasks.register("verifyReferenceSnapshots") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Explicitly verifies approved external workspace reference snapshots."

  doLast {
    val configuredRoot = providers.gradleProperty("riverReferenceWorkspaceRoot").orNull
        ?: throw GradleException(
          "verifyReferenceSnapshots requires -PriverReferenceWorkspaceRoot="
              + "/absolute/path/to/workspace"
        )
    val workspaceRoot = java.nio.file.Path.of(configuredRoot)
    require(workspaceRoot.isAbsolute) {
      "riverReferenceWorkspaceRoot must be absolute"
    }
    val rows = ProvenancePolicy.read(
      rootDir.toPath().resolve("docs/governance/provenance-ledger.csv")
    )
    val identities = ProvenancePolicy.verifyExternalReferences(
      workspaceRoot,
      rows.values
    )
    identities.toSortedMap().forEach { (artifactId, identity) ->
      logger.lifecycle(
        "$artifactId ${identity.sha256()} ${identity.fileCount()} regular files"
      )
    }
    val selected = LegacyEvidencePolicy.verify(
      rootDir.toPath().resolve("docs/compatibility/legacy-support-matrix.csv"),
      workspaceRoot,
      rows
    )
    logger.lifecycle("legacy-support-matrix $selected selected files")
  }
}

val verifyProvenancePolicyFixtures = tasks.register("verifyProvenancePolicyFixtures") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Runs fail-closed provenance and snapshot negative fixtures."

  doLast {
    val canonical = Files.readAllLines(
      rootDir.toPath().resolve("docs/governance/provenance-ledger.csv")
    )

    fun expectFailure(label: String, expected: String, action: () -> Unit) {
      val failure = runCatching(action).exceptionOrNull()
          ?: throw GradleException("$label fixture unexpectedly passed")
      require(expected in (failure.message ?: "")) {
        "$label fixture failed for the wrong reason: $failure"
      }
    }

    val malformedReference = canonical.map { line ->
      if (line.startsWith("legacy-ingres-source,")) {
        line.replace(
          "http://code.ingres.com/ingres/main,svn-r3970",
          "../ingres,svn-r3970"
        )
      } else {
        line
      }
    }
    expectFailure("malformed-reference", "upstream must be an absolute HTTP(S) source URL") {
      ProvenancePolicy.parse(malformedReference)
    }

    val pendingApproval = canonical.map { line ->
      if (line.startsWith("legacy-ingres-source,")) {
        line.substringBeforeLast(',') + ",pending project review"
      } else {
        line
      }
    }
    expectFailure("pending-approval", "approval is unresolved") {
      ProvenancePolicy.parse(pendingApproval)
    }

    val missingNotice = canonical.map { line ->
      if (line.startsWith("legacy-ingres-source,")) {
        line.replace(",external-file:README.txt,", ",,")
      } else {
        line
      }
    }
    expectFailure("missing-notice", "notice outcome is malformed") {
      ProvenancePolicy.parse(missingNotice)
    }

    val rows = ProvenancePolicy.parse(canonical)
    expectFailure("dependency-drift", "not resolved by the build") {
      ProvenancePolicy.verifyResolvedDependencies(rows.values, emptyMap())
    }

    val workspace = temporaryDir.toPath().resolve("workspace")
    val tree = workspace.resolve("fixture")
    Files.createDirectories(tree)
    Files.writeString(tree.resolve("README.txt"), "notice evidence\n")
    Files.writeString(tree.resolve("payload.bin"), "fixture payload\n")
    val identity = ProvenancePolicy.treeIdentity(tree)
    Files.writeString(tree.resolve(".DS_Store"), "ignored Finder metadata\n")
    require(ProvenancePolicy.treeIdentity(tree) == identity) {
      "regular .DS_Store changed the reference tree identity"
    }
    Files.writeString(tree.resolve("payload.DS_Store"), "retained near name\n")
    require(ProvenancePolicy.treeIdentity(tree) != identity) {
      "near-name metadata fixture was incorrectly excluded"
    }
    Files.delete(tree.resolve("payload.DS_Store"))
    Files.delete(tree.resolve(".DS_Store"))
    Files.createDirectory(tree.resolve(".DS_Store"))
    Files.writeString(tree.resolve(".DS_Store/retained.bin"), "retained directory\n")
    require(ProvenancePolicy.treeIdentity(tree) != identity) {
      "directory named .DS_Store was incorrectly excluded"
    }
    Files.delete(tree.resolve(".DS_Store/retained.bin"))
    Files.delete(tree.resolve(".DS_Store"))
    val metadataOnlyTree = workspace.resolve("metadata-only")
    Files.createDirectories(metadataOnlyTree)
    Files.writeString(metadataOnlyTree.resolve(".DS_Store"), "ignored Finder metadata\n")
    expectFailure("metadata-only-tree", "snapshot tree has no regular files") {
      ProvenancePolicy.treeIdentity(metadataOnlyTree)
    }
    val staleLedger = listOf(
      ProvenancePolicy.HEADER,
      listOf(
        "fixture-reference",
        "reference",
        "Fixture reference tree",
        "https://example.invalid/reference",
        "snapshot-1",
        "external-workspace:fixture",
        "0000000000000000000000000000000000000000000000000000000000000000",
        ProvenancePolicy.TREE_DIGEST,
        "LicenseRef-Fixture",
        "external-file:README.txt",
        "negative fixture",
        "external workspace reference",
        "approved:2026-08-09:project-owner-decision"
      ).joinToString(",")
    )
    val staleRows = ProvenancePolicy.parse(staleLedger)
    expectFailure("stale-digest", "reference snapshot digest is stale") {
      ProvenancePolicy.verifyExternalReferences(workspace, staleRows.values)
    }

    val matrix = temporaryDir.toPath().resolve("legacy-support-matrix.csv")
    fun selectedMatrix(path: String): List<String> =
      listOf(
        LegacyEvidencePolicy.HEADER,
        listOf(
          "fixture-reference",
          "snapshot-1",
          path,
          "0000000000000000000000000000000000000000000000000000000000000000",
          "fixture-reference",
          "negative digest fixture",
          "adapt",
          "U05",
          "selected file identity",
          "00000",
          "build-policy",
          "independent fixture"
        ).joinToString(",")
      )
    Files.write(matrix, selectedMatrix("payload.bin"))
    expectFailure("selected-file-digest", "selected source digest mismatch") {
      LegacyEvidencePolicy.verify(matrix, workspace, staleRows)
    }
    Files.write(matrix, selectedMatrix("sub/../payload.bin"))
    expectFailure("selected-file-path", "source path is not canonical") {
      LegacyEvidencePolicy.verify(matrix, workspace, staleRows)
    }
    Files.write(matrix, selectedMatrix(".DS_Store"))
    expectFailure("selected-file-exclusion", "excluded from the reference identity") {
      LegacyEvidencePolicy.verify(matrix, workspace, staleRows)
    }
  }
}

tasks.named("check") {
  dependsOn(
    verifySourcePolicy,
    verifySqlShapeSourcePolicy,
    verifySqlShapeSourcePolicyFixtures,
    verifyModuleGraph,
    verifyBuildPolicyFixtures,
    verifyProjectDependencyVisibility,
    verifyHotPathBytecode,
    verifyHotPathBytecodeFixtures,
    verifyIndexedTableClassReferences,
    verifyClassReferencePolicyFixtures,
    verifySqlRuntimeInvocationPolicy,
    verifyInvocationPolicyFixtures,
    verifyDependencyLedger,
    verifyProvenancePolicyFixtures
  )
  dependsOn(subprojects.map { it.tasks.named("check") })
}
