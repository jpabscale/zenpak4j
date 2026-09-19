// Copyright (c) 2026 jpabscale — template-mode tests (game-gated fixtures; hermetic assertions).
package com.github.jpabscale.zenpak4j.retoc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Template mode against the real cooker fixture: append-only name/import/export edits ship
 * chunks that validate and keep every existing payload byte-identical, and add+remove round-trips
 * the tables (except the name map, which never shrinks).
 */
class ZenTemplateTest {
    private val cookerChunk: Path = Path.of(
        System.getenv("SB_PKG_CHUNK") ?: "/tmp/opencode/hb-work/dump-test-game/CH_P_EVE_01_Blueprint.uasset")
    private val headerChunk: Path = Path.of(
        System.getenv("SB_HEADER_CHUNK") ?: "/tmp/opencode/raw-keepoutfit/chunks/9bdb3fceb6b7da900000000a")
    private val packageId = (System.getenv("SB_PACKAGE_ID") ?: "9582239030029544658").toULong()

    private fun storeEntry(): StoreEntry {
        val container = FIoContainerHeader.deserialize(
            ByteArrayInputStream(Files.readAllBytes(headerChunk)), null)
        return container.get_store_entry(FPackageId(packageId))!!
    }

    private fun dump(chunk: ByteArray): ObjectNode = zen_package_to_json_node(
        chunk, true, EIoContainerHeaderVersion.Initial, EIoStoreTocVersion.PartitionSize, storeEntry())

    private fun fixture(): ByteArray? {
        assumeTrue(Files.isRegularFile(cookerChunk), "cooker fixture missing at $cookerChunk")
        assumeTrue(Files.isRegularFile(headerChunk), "container header fixture missing at $headerChunk")
        return Files.readAllBytes(cookerChunk)
    }

    @Test
    fun appendedNamesKeepEveryExistingIndexAndWriteACleanChunk() {
        val chunk = fixture() ?: return
        val node = dump(chunk)
        val count = node.path("name_map").path("raw_names_b64").size()
        assertTrue(count > 0, "fixture has names")

        assertEquals(count, zen_package_append_name(node, "TemplateModeTestName"))
        assertNull(zen_package_append_name(node, "TemplateModeTestName"), "second append is a no-op")
        assertEquals(count + 1, node.path("name_map").path("raw_names_b64").size())

        val written = zen_package_write(node, storeEntry())
        assertTrue(zen_package_validate(written.chunk).isEmpty(),
            "name append must validate: ${zen_package_validate(written.chunk)}")
        assertEquals(written.chunk.size.toULong(), written.store_entry.export_bundles_size)

        val names = zen_package_to_json_node(
            written.chunk, false, EIoContainerHeaderVersion.Initial,
            EIoStoreTocVersion.PartitionSize, storeEntry()).path("name_map").path("names")
        assertEquals("TemplateModeTestName", names[count].asText())

        // existing exports keep their exact payload bytes
        val before = extract_zen_payloads(chunk)
        val after = extract_zen_payloads(written.chunk)
        assertEquals(before.size, after.size)
        for (i in before.indices) assertArrayEquals(before[i], after[i], "payload $i changed")
    }

    @Test
    fun appendedImportsAreIdempotentAndRegisterTheirPackage() {
        val chunk = fixture() ?: return
        val node = dump(chunk)
        val path = "/Game/Art/Character/PC/CH_P_EVE_01/Blueprints/CH_P_EVE_01_Blueprint_Mod" +
            ".CH_P_EVE_01_Blueprint_Mod_C"

        val index = zen_package_add_import(node, ZenImportSpec(path, importedBundle = 0))
        assertEquals(480, index, "the fixture has 480 imports")
        assertEquals(index, zen_package_add_import(node, ZenImportSpec(path, importedBundle = 0)))
        assertEquals(481, node.path("import_map").size())
        // a script import that is already in the map returns its index; a new one appends
        assertEquals(61, zen_package_add_import(node, ZenImportSpec("/Script/Engine.Actor")))
        assertEquals(481, zen_package_add_import(node, ZenImportSpec("/Script/Engine.TemplateModeTestScript")))
        assertEquals(482, node.path("import_map").size())

        val entry = storeEntry()
        val packagesBefore = entry.imported_packages.size
        val written = zen_package_write(node, entry)
        assertTrue(zen_package_validate(written.chunk).isEmpty(),
            "import append must validate: ${zen_package_validate(written.chunk)}")
        assertEquals(chunk.size + 16, written.chunk.size, "two dense import-map entries")
        val imported = FPackageId.from_name("/Game/Art/Character/PC/CH_P_EVE_01/Blueprints/CH_P_EVE_01_Blueprint_Mod")
        assertTrue(written.store_entry.imported_packages.contains(imported), "package id in the store entry")
        assertEquals(packagesBefore + 1, written.store_entry.imported_packages.size)
    }

    @Test
    fun addedExportShipsItsOwnBundleAndPayload() {
        val chunk = fixture() ?: return
        val node = dump(chunk)
        val index = zen_package_add_export(
            node,
            ZenExportSpec(
                objectName = "TemplateModeTestExport",
                payload = "template-mode".toByteArray(),
                classIndex = FPackageObjectIndex.create_script_import("/Script/Engine.Actor"),
                outerIndex = FPackageObjectIndex.create_export(0u),
            ),
        )
        assertEquals(114, index, "appended after the fixture's 114 exports")
        assertEquals(115, node.path("export_map").size())
        assertEquals(3, node.path("export_bundle_headers").size())
        assertEquals(230, node.path("export_bundle_entries").size())
        assertEquals(2, bundle_index_of(node, index))

        val written = zen_package_write(node, storeEntry())
        assertTrue(zen_package_validate(written.chunk).isEmpty(),
            "added export must validate: ${zen_package_validate(written.chunk)}")
        assertEquals(115, written.store_entry.export_count)
        assertEquals(3, written.store_entry.export_bundle_count)
        assertEquals(written.chunk.size.toULong(), written.store_entry.export_bundles_size)

        val payloads = extract_zen_payloads(written.chunk)
        assertArrayEquals("template-mode".toByteArray(), payloads[114])
        val before = extract_zen_payloads(chunk)
        for (i in before.indices) assertArrayEquals(before[i], payloads[i], "payload $i changed")

        // the redumped package resolves the new export and its bundle
        val redumped = zen_package_to_json_node(
            written.chunk, false, EIoContainerHeaderVersion.Initial,
            EIoStoreTocVersion.PartitionSize, written.store_entry)
        assertEquals("TemplateModeTestExport",
            redumped.path("export_map").path(114).path("object_name_resolved").asText())
        assertEquals(115, redumped.path("export_map").size())
        assertEquals(3, redumped.path("export_bundle_headers").size())
        assertEquals(2, bundle_index_of(redumped, 114))
    }

    @Test
    fun addedThenRemovedExportRestoresTheTablesAndPayloads() {
        val chunk = fixture() ?: return
        val node = dump(chunk)
        val ops = ObjectMapper().readTree(
            """
            {"names":["TemplateModeTestName"],
             "imports":[{"object_path":"/Game/Art/Character/PC/CH_P_EVE_01/Blueprints/CH_P_EVE_01_Blueprint_Mod.CH_P_EVE_01_Blueprint_Mod_C","imported_bundle":0}],
             "add_exports":[{"object_name":"TemplateModeTestExport","payload_b64":"${java.util.Base64.getEncoder().encodeToString("template-mode".toByteArray())}","class":{"import":480},"outer":{"export":0},"dependencies":[480]}]}
            """.trimIndent())
        val added = zen_package_apply_template(node, ops)
        assertEquals(3, added.total)
        val index = 114
        assertEquals(2, node.path("export_bundle_entries").count { it.path("local_export_index").asInt() == index })

        val removedOps = ObjectMapper().readTree("""{"remove_exports":[{"index":$index,"force":true}]}""")
        val removed = zen_package_apply_template(node, removedOps)
        assertEquals(1, removed.total)
        assertEquals(114, node.path("export_map").size())
        assertEquals(2, node.path("export_bundle_headers").size(), "the added bundle is pruned")
        assertEquals(228, node.path("export_bundle_entries").size())

        val written = zen_package_write(node, storeEntry())
        assertTrue(zen_package_validate(written.chunk).isEmpty(),
            "round trip must validate: ${zen_package_validate(written.chunk)}")
        assertEquals(114, written.store_entry.export_count)
        assertEquals(2, written.store_entry.export_bundle_count)
        val before = extract_zen_payloads(chunk)
        val after = extract_zen_payloads(written.chunk)
        assertEquals(before.size, after.size)
        for (i in before.indices) assertArrayEquals(before[i], after[i], "payload $i changed")

        // the dependency arc that the added export created is gone with its bundle
        assertEquals(0, node.path("internal_dependency_arcs").size())
    }

    @Test
    fun removingAReferencedOrArcTargetedExportIsRefused() {
        val chunk = fixture() ?: return
        val node = dump(chunk)
        val referenced = referencedExportIndices(node)
        assumeTrue(referenced.isNotEmpty(), "fixture has no table-referenced export")
        val failure = runCatching { zen_package_remove_export(node, referenced.first()) }.exceptionOrNull()
        assertNotNull(failure, "removing a referenced export must be refused")
        assertTrue(failure!!.message!!.contains("reference"), "refusal names the reference: ${failure.message}")

        val unsupported = dump(chunk)
        unsupported.put("container_header_version", EIoContainerHeaderVersion.NoExportInfo.name)
        val refused = runCatching {
            zen_package_add_import(unsupported, ZenImportSpec("/Game/Made/Up.Up_C"))
        }.exceptionOrNull()
        assertNotNull(refused, "newer container layouts are refused until their index tables exist")
        assertTrue(refused!!.message!!.contains("Initial"), "refusal explains the layout")
    }

    @Test
    fun removalRefusalsHappenBeforeAnyMutation() {
        // A synthetic package: two exports, one bundle each, and an internal arc that depends on
        // the second bundle. Removing that export must refuse *before* touching the node.
        val node = syntheticRemovalNode()
        val snapshot = node.toString()
        node.putArray("internal_dependency_arcs").addObject()
            .put("from_export_bundle_index", 1)
            .put("to_export_bundle_index", 0)
        val withArc = node.toString()
        val failure = runCatching { zen_package_remove_export(node, 1, force = true) }
            .exceptionOrNull()
        assertNotNull(failure, "a bundle that others depend on must not be pruned")
        assertTrue(failure!!.message!!.contains("depends"), "refusal names the dependent: ${failure.message}")
        assertEquals(withArc, node.toString(), "the refusal must not leave a half-mutated node")
        assertTrue(snapshot.isNotEmpty())
    }

    @Test
    fun removalWithoutForceIsRefusedAndForceRemovesCleanly() {
        val unforced = syntheticRemovalNode()
        val failure = runCatching { zen_package_remove_export(unforced, 1) }.exceptionOrNull()
        assertNotNull(failure, "removal without force must be refused")
        assertTrue(failure!!.message!!.contains("force"), "refusal names force: ${failure.message}")

        val forced = syntheticRemovalNode()
        zen_package_remove_export(forced, 1, force = true)
        assertEquals(1, forced.path("export_map").size())
        assertEquals(1, forced.path("payloads").size())
        assertEquals(0, forced.path("payloads").path(0).path("index").asInt(), "payload index refreshed")
        assertEquals(1, forced.path("export_bundle_headers").size(), "the emptied bundle is pruned")
    }

    @Test
    fun objectIndexReferencesAreValidated() {
        val node = syntheticRemovalNode()
        val mapper = ObjectMapper()
        assertNotNull(
            runCatching { object_index_from_ref(node, mapper.readTree("""{"export": 9}""")) }
                .exceptionOrNull(),
            "an out-of-range export reference must be refused")
        assertNotNull(
            runCatching {
                object_index_from_ref(node, mapper.readTree("""{"object_path": "/Game/Nope.Nope"}"""))
            }.exceptionOrNull(),
            "an object_path that is not in the import map must be refused")
    }

    /** A minimal two-export package: separate bundles, one import, no arcs. */
    private fun syntheticRemovalNode(): ObjectNode {
        val node = ObjectMapper().createObjectNode()
        node.put("container_header_version", EIoContainerHeaderVersion.Initial.name)
        node.putObject("summary").put("cooked_header_size", "100")
        node.putObject("name_map")
            .put("kind", EMappedNameType.Package.name)
        val exports = node.putArray("export_map")
        for (i in 0 until 2) {
            val e = exports.addObject()
            e.put("cooked_serial_offset", (100 + i * 10).toString())
            e.put("cooked_serial_size", "10")
            e.putObject("object_name").put("index_and_type", i.toLong()).put("number", 0)
            e.put("object_name_resolved", "Object$i")
            for (field in listOf("outer_index", "class_index", "super_index", "template_index")) {
                e.putObject(field).put("type_and_id", FPackageObjectIndex.create_null().type_and_id.toString())
            }
        }
        val payloads = node.putArray("payloads")
        for (i in 0 until 2) payloads.addObject().put("index", i).put("size", 10)
        val headers = node.putArray("export_bundle_headers")
        val entries = node.putArray("export_bundle_entries")
        for (i in 0 until 2) {
            headers.addObject().put("serial_offset", (i * 10).toString())
                .put("first_entry_index", i * 2).put("entry_count", 2)
            entries.addObject().put("local_export_index", i).put("command_type", "Create")
            entries.addObject().put("local_export_index", i).put("command_type", "Serialize")
        }
        node.putArray("internal_dependency_arcs")
        node.putArray("dependency_bundle_entries")
        node.putArray("external_package_dependencies")
        node.putArray("imported_packages")
        node.putArray("payload_count")
        return node
    }

    /** Export indices that appear in another export's outer/class/super/template index. */
    private fun referencedExportIndices(node: ObjectNode): List<Int> {
        val fields = listOf("outer_index", "class_index", "super_index", "template_index")
        val refs = HashSet<Int>()
        for (e in node.path("export_map")) {
            for (field in fields) {
                val ref = FPackageObjectIndex(e.path(field).path("type_and_id").asText("0").toULong())
                if (ref.kind() == FPackageObjectIndexType.Export) refs += ref.raw_index().toInt()
            }
        }
        return refs.sorted()
    }
}
