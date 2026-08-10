import io.riverdb.buildpolicy.BuildPolicy
import io.riverdb.buildpolicy.HotPathBytecodeFixtureMutator
import io.riverdb.buildpolicy.HotPathBytecodePolicy
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
version = "0.1.0-SNAPSHOT"

val productionModules = listOf(
  "river-base",
  "river-observability-api",
  "river-platform",
  "river-format",
  "river-tx-api",
  "river-journal-api",
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
  "river-journal-api" to setOf("river-format", "river-observability-api"),
  "river-wal" to setOf(
    "river-journal-api", "river-platform", "river-format",
    "river-observability-api"
  ),
  "river-buffer" to setOf(
    "river-journal-api", "river-platform", "river-format",
    "river-observability-api"
  ),
  "river-storage" to setOf(
    "river-format", "river-journal-api", "river-buffer", "river-tx-api",
    "river-observability-api"
  ),
  "river-tx" to setOf(
    "river-tx-api", "river-journal-api", "river-observability-api"
  ),
  "river-recovery" to setOf(
    "river-journal-api", "river-wal", "river-buffer", "river-storage",
    "river-tx", "river-tx-api"
  ),
  "river-backup" to setOf(
    "river-journal-api", "river-platform", "river-format", "river-wal",
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
    "river-journal-api", "river-platform", "river-format", "river-wal",
    "river-buffer", "river-storage", "river-tx-api", "river-tx",
    "river-recovery", "river-backup", "river-catalog", "river-sql",
    "river-planner", "river-exec", "river-engine-api"
  ),
  "river-protocol" to setOf("river-engine-api"),
  "river-client" to setOf("river-protocol", "river-engine-api"),
  "river-server" to setOf("river-protocol", "river-engine-api", "river-engine"),
  "river-jdbc" to setOf("river-client"),
  "river-cli" to setOf("river-client"),
  "river-admin" to setOf("river-client", "river-engine-api", "river-backup"),
  "river-inspect" to setOf("river-platform", "river-format", "river-wal"),
  "river-migration" to setOf("river-client"),
  "river-observability" to setOf("river-observability-api"),
  "river-testkit" to productionModules.toSet(),
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
  "river-journal-api" to setOf("river-base"),
  "river-wal" to setOf("river-base", "river-format", "river-platform"),
  "river-storage" to setOf("river-base"),
  "river-tx" to setOf("river-base", "river-tx-api"),
  "river-sql" to setOf("river-base"),
  "river-engine" to setOf(
    "river-base", "river-format", "river-platform", "river-storage",
    "river-tx", "river-tx-api", "river-wal", "river-sql"
  ),
  "river-inspect" to setOf("river-base", "river-format", "river-platform"),
  "river-testkit" to setOf(
    "river-base", "river-platform", "river-tx-api", "river-journal-api"
  ),
  "river-bench" to setOf("river-base")
)

// Project dependencies are compile-private unless a current River consumer
// must compile against a type exposed by a dependency. Keep this allowset exact:
// adding an entry changes downstream compile visibility and requires P03 review.
val approvedApiDependencies = emptyMap<String, Set<String>>()

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
val indexedPageStoreDescriptor = "Lio/riverdb/engine/page/IndexedPageStore;"
val indexedScanCursorDescriptor = "Lio/riverdb/engine/table/IndexedScanCursor;"
val indexedScanResultDescriptor = "Lio/riverdb/engine/table/IndexedScanResult;"
val relationalScanCursorDescriptor = "Lio/riverdb/engine/relational/RelationalScanCursor;"
val relationalScanResultDescriptor = "Lio/riverdb/engine/relational/RelationalScanResult;"
val sqlCommandDescriptor = "Lio/riverdb/sql/SqlCommand;"
val sqlExecutionResultDescriptor = "Lio/riverdb/engine/sql/SqlExecutionResult;"
val sqlScanCursorDescriptor = "Lio/riverdb/engine/sql/SqlScanCursor;"
val sqlScanRowResultDescriptor = "Lio/riverdb/engine/sql/SqlScanRowResult;"
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
    "io.riverdb.engine.page.IndexedPageStore",
    "appendPreparedInsertBatch",
    "(JJ$longArrayDescriptor$byteBufferDescriptor"
        + "I${intArrayDescriptor}I$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "appendPreparedMutationBatch",
    "(JJ$intArrayDescriptor$longArrayDescriptor$intArrayDescriptor"
        + "$byteBufferDescriptor" + "I${intArrayDescriptor}I"
        + "$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "forcePreparedInserts",
    "()$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "publishForcedInserts",
    "()$statusCodeDescriptor"
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
    "($byteBufferDescriptor" + "J$btreeLookupResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.storage.btree.BTreePage",
    "childForKey",
    "($byteBufferDescriptor" + "J)I"
  ),
  hotMethod(
    "io.riverdb.storage.btree.BTreePage",
    "insertLeaf",
    "($byteBufferDescriptor" + "JI)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.storage.btree.BTreePage",
    "updateLeaf",
    "($byteBufferDescriptor" + "JI)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.storage.btree.BTreePage",
    "splitLeaf",
    "($byteBufferDescriptor$byteBufferDescriptor"
        + "IJI$btreeSplitResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.storage.btree.BTreePage",
    "insertInternal",
    "($byteBufferDescriptor" + "JI)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "commitInsert",
    "(JJJ$byteBufferDescriptor$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "commitInsertBatch",
    "(JJ$longArrayDescriptor$byteBufferDescriptor"
        + "I${intArrayDescriptor}I$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "commitMutationBatch",
    "(JJ$intArrayDescriptor$longArrayDescriptor$intArrayDescriptor"
        + "$byteBufferDescriptor" + "I${intArrayDescriptor}I"
        + "$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "applyInsertOperation",
    "($byteBufferDescriptor" + "JJJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "applyInsertBatchOperation",
    "($byteBufferDescriptor" + "JJJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "batchContainsEarlierKey",
    "($byteBufferDescriptor" + "IJ)Z"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "countEarlierBatchEntriesInLeaf",
    "($byteBufferDescriptor" + "II)I"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "applyMutationBatchOperation",
    "($byteBufferDescriptor" + "JJJ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "mutationContainsEarlierKey",
    "($byteBufferDescriptor" + "IJ)Z"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "countEarlierMutationInsertsInLeaf",
    "($byteBufferDescriptor" + "II)I"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "rowCommitSequence",
    "(I)J"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "previousRowId",
    "(I)I"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "isDeletedRow",
    "(I)Z"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "stageExisting",
    "(I)$byteBufferDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "operationPayload",
    "(I)$byteBufferDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "stageRow",
    "($byteBufferDescriptor" + "II$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "canAppendRow",
    "(I)Z"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "fetchRow",
    "(I$heapRowResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "rowLength",
    "(I)I"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "copyRowTo",
    "(I$byteBufferDescriptor" + "I)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "canAppendRows",
    "(${intArrayDescriptor}I)Z"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "canAppendEncodedRows",
    "($byteBufferDescriptor" + "IIII)Z"
  ),
  hotMethod(
    "io.riverdb.engine.page.IndexedPageStore",
    "appendCurrentRow",
    "($byteBufferDescriptor" + "IIIJJJIZ)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTable",
    "insert",
    "(JJ$byteBufferDescriptor$heapInsertResultDescriptor)$statusCodeDescriptor"
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
    "io.riverdb.engine.table.IndexedTable",
    "commitInsert",
    "(JJ$byteBufferDescriptor$indexedCommitResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTable",
    "commitInserts",
    "(J$longArrayDescriptor$byteBufferDescriptor"
        + "I${intArrayDescriptor}I$indexedCommitResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTable",
    "commitMutations",
    "(J$intArrayDescriptor$longArrayDescriptor$intArrayDescriptor"
        + "$byteBufferDescriptor" + "I${intArrayDescriptor}I"
        + "$indexedCommitResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTable",
    "prepareMutation",
    "(JJ$indexedMutationTargetDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTable",
    "prepareInsert",
    "(JJ$indexedMutationTargetDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedMutationTarget",
    "rowId",
    "()I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedMutationTarget",
    "set",
    "(I)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedMutationTarget",
    "reset",
    "()V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTable",
    "insertCommitted",
    "(JJJ$byteBufferDescriptor$heapInsertResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTable",
    "splitAndInsert",
    "(I$byteBufferDescriptor" + "JI)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTable",
    "findOperationLeafPageId",
    "(J)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTable",
    "versionRowsInLeaf",
    "($indexedPageStoreDescriptor$byteBufferDescriptor" + "I)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTable",
    "fetchByKey",
    "(J$heapRowResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTable",
    "fetchByKeyAt",
    "(JJ$heapRowResultDescriptor)$statusCodeDescriptor"
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
    "(J$lockScopeDescriptor" + "JJ$lockModeDescriptor"
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
    "io.riverdb.sql.SqlParser",
    "parse",
    "(Ljava/lang/String;$sqlCommandDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.sql.SqlSession",
    "execute",
    "(Ljava/lang/String;$sqlExecutionResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTable",
    "beginScan",
    "(JJJ$indexedScanCursorDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTable",
    "nextScan",
    "($indexedScanCursorDescriptor$indexedScanResultDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "beginScan",
    "(JJ$indexedScanCursorDescriptor)$statusCodeDescriptor"
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
    "io.riverdb.engine.table.IndexedTransactionSession",
    "insert",
    "(J$byteBufferDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "update",
    "(J$byteBufferDescriptor)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "delete",
    "(J)$statusCodeDescriptor"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "appendPending",
    "(IJI$byteBufferDescriptor" + "IIZ)V"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "fetchByKey",
    "(J$heapRowResultDescriptor)$statusCodeDescriptor"
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
    "(J)I"
  ),
  hotMethod(
    "io.riverdb.engine.table.IndexedTransactionSession",
    "acquireExclusiveKey",
    "(J)$statusCodeDescriptor"
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
  description = "Checks tabs, two-space source indentation, and internal package boundaries."

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

val verifyModuleGraph = tasks.register("verifyModuleGraph") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Rejects River project dependencies outside the approved module DAG."

  doLast {
    val actualGraph = linkedMapOf<String, Set<String>>()
    subprojects.forEach { module ->
      actualGraph[module.name] = BuildPolicy.inheritedProjectDependencies(
        listOfNotNull(
          module.configurations.getByName("compileClasspath"),
          module.configurations.getByName("runtimeClasspath"),
          module.configurations.findByName("testFixturesImplementation")
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
    subprojects.forEach { module ->
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
  description = "Assembles every production, testkit, and benchmark JAR for comparison."
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
  description = "Verifies every resolved external JAR against the provenance ledger."
  dependsOn(externalDependencyReports.values)

  doLast {
    val ledgerPath = rootDir.toPath().resolve("docs/governance/provenance-ledger.csv")
    val ledgerLines = Files.readAllLines(ledgerPath)
    require(ledgerLines.isNotEmpty()) { "provenance ledger is empty" }
    require(
      ledgerLines.first()
          == "artifact_id,artifact_type,name,upstream,version,sha256,license,use,vendoring,approval"
    ) { "provenance ledger header does not match the v1 schema" }

    val artifactRows = linkedMapOf<String, List<String>>()
    val dependencyRows = linkedMapOf<String, List<String>>()
    ledgerLines.drop(1).forEachIndexed { index, line ->
      val fields = line.split(',')
      require(fields.size == 10) {
        "provenance ledger line ${index + 2} has ${fields.size} fields, expected 10"
      }
      require(artifactRows.put(fields[0], fields) == null) {
        "duplicate provenance artifact ID ${fields[0]}"
      }
      require(fields[1] in setOf("source", "reference", "dependency", "tool")) {
        "unknown provenance artifact type ${fields[1]} at line ${index + 2}"
      }
      require(fields[9].startsWith("approved ")) {
        "provenance approval must fail closed at line ${index + 2}"
      }
      if (fields[1] == "dependency" || fields[1] == "tool") {
        require(
          fields[0].isNotBlank()
              && fields[2].isNotBlank()
              && fields[3].isNotBlank()
              && fields[4].isNotBlank()
              && fields[5].matches(Regex("[0-9a-f]{64}"))
              && fields[6].isNotBlank()
              && fields[7].isNotBlank()
              && fields[8].isNotBlank()
              && fields[9].isNotBlank()
        ) { "provenance ${fields[1]} row is incomplete at line ${index + 2}" }
      }
      if (fields[1] == "source") {
        require(
          fields[0].isNotBlank()
              && fields[2].isNotBlank()
              && fields[4].isNotBlank()
              && fields[6].isNotBlank()
              && fields[7].isNotBlank()
              && fields[8].isNotBlank()
        ) { "provenance source row is incomplete at line ${index + 2}" }
      }
      if (fields[1] == "reference") {
        require(
          fields[0].isNotBlank()
              && fields[2].isNotBlank()
              && fields[3].isNotBlank()
              && fields[4].isNotBlank()
              && fields[5].matches(Regex("[0-9a-f]{64}"))
              && fields[6].isNotBlank()
              && fields[7].isNotBlank()
              && fields[8].isNotBlank()
        ) { "provenance reference row is incomplete at line ${index + 2}" }
      }
      if (fields[1] == "dependency") {
        require(fields[2].count { it == ':' } == 1) {
          "provenance dependency coordinate must be group:name at line ${index + 2}"
        }
        val key = "${fields[2]}:${fields[4]}"
        require(dependencyRows.put(key, fields) == null) {
          "duplicate provenance dependency $key"
        }
      }
    }

    val wrapperJarRow = artifactRows.getValue("gradle-wrapper-jar")
    val wrapperJarChecksum = sha256(rootDir.resolve("gradle/wrapper/gradle-wrapper.jar"))
    require(wrapperJarRow[5] == wrapperJarChecksum) {
      "Gradle wrapper JAR checksum does not match the provenance ledger"
    }
    val wrapperProperties = java.util.Properties()
    rootDir.resolve("gradle/wrapper/gradle-wrapper.properties").inputStream().use {
      wrapperProperties.load(it)
    }
    val distributionRow = artifactRows.getValue("gradle-distribution")
    require(distributionRow[5] == wrapperProperties.getProperty("distributionSha256Sum")) {
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

    val missingRows = resolved.keys - dependencyRows.keys
    val staleRows = dependencyRows.keys - resolved.keys
    require(missingRows.isEmpty()) {
      "resolved dependencies missing from provenance ledger: ${missingRows.sorted()}"
    }
    require(staleRows.isEmpty()) {
      "provenance dependency rows are not resolved by the build: ${staleRows.sorted()}"
    }

    resolved.forEach { (key, actual) ->
      val expected = dependencyRows.getValue(key)[5]
      require(actual == expected) {
        "provenance checksum mismatch for $key: expected $expected, got $actual"
      }
    }
  }
}

tasks.named("check") {
  dependsOn(
    verifySourcePolicy,
    verifyModuleGraph,
    verifyBuildPolicyFixtures,
    verifyProjectDependencyVisibility,
    verifyHotPathBytecode,
    verifyHotPathBytecodeFixtures,
    verifyDependencyLedger
  )
  dependsOn(subprojects.map { it.tasks.named("check") })
}
