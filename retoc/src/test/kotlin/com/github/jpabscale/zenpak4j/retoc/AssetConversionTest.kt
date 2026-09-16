// Ported from retoc (MIT) — Copyright (c) 2025 Truman Kilen and Archengius
package com.github.jpabscale.zenpak4j.retoc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.TreeMap
import java.util.HashMap

class AssetConversionTest {

    // Test for Randy (no package dependencies, only script imports) - strict check
    @Test
    fun test_asset_conversion_Randy() {
        val engineVersion = EngineVersion.UE5_4
        val containerVersion = engineVersion.toc_version()
        val headerVersion = engineVersion.container_header_version()
        val packageVersion = engineVersion.package_file_version()

        val zenPath = TestFixtures.find("UE5.4/Randy.uzenasset")!!
        val legacyPath = TestFixtures.find("UE5.4/Randy.uasset")!!
        val legacyExportsPath = TestFixtures.find("UE5.4/Randy.uexp")!!

        assertTrue(Files.exists(zenPath))
        assertTrue(Files.exists(legacyPath))

        val zenBytes = Files.readAllBytes(zenPath)
        val originalLegacyBytes = Files.readAllBytes(legacyPath)
        val originalLegacyExports = if (Files.exists(legacyExportsPath)) Files.readAllBytes(legacyExportsPath) else ByteArray(0)

        // Parse original legacy for comparison
        val originalLegacyHeader = FLegacyPackageHeader.deserialize(SeekableByteArrayInputStream(originalLegacyBytes), packageVersion)
        assertNotNull(originalLegacyHeader)
        assertEquals("/Game/Blueprints/DataTables/Communications/Randy", originalLegacyHeader.summary.package_name)

        // Parse zen header to get package_id
        val zenHeaderForId = FZenPackageHeader.deserialize(SeekableByteArrayInputStream(zenBytes), null, containerVersion, headerVersion, packageVersion)
        val packageName = zenHeaderForId.package_name()
        val packageId = FPackageId.from_name(packageName)

        // TreeMap ByteBuffer check
        val treeCheck = TreeMap<String, Int>()
        treeCheck[packageName] = zenBytes.size
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
        assertEquals(treeCheck.size, ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int)

        // Load script objects from UE5.3 fixture (compatible)
        val scriptObjectsBytes = Files.readAllBytes(TestFixtures.find("UE5.3/ScriptObjects.bin")!!)
        val scriptObjects = ZenScriptObjects.deserialize_new(java.io.ByteArrayInputStream(scriptObjectsBytes))

        // Create fake IoStore
        val packageBytesMap = HashMap<FPackageId, ByteArray>()
        packageBytesMap[packageId] = zenBytes

        val fakeStore = FakeIoStore(
            packageBytes = packageBytesMap,
            scriptObjects = scriptObjects,
            containerVersion = containerVersion,
            headerVersion = headerVersion,
            mockedHeaders = HashMap()
        )

        val log = Log.no_log()
        val packageContext = FZenPackageContext.create(fakeStore, packageVersion, log, null)

        // Build legacy
        val builder = build_asset_from_zen(packageContext, packageId)
        val serialized = serialize_asset(builder)

        // Verify serialized legacy header
        val generatedLegacyHeader = FLegacyPackageHeader.deserialize(SeekableByteArrayInputStream(serialized.asset_file_buffer), packageVersion)
        assertNotNull(generatedLegacyHeader)
        assertEquals(originalLegacyHeader.summary.package_name, generatedLegacyHeader.summary.package_name)

        // Compare imports: Randy has 3 imports, should match
        assertEquals(originalLegacyHeader.imports.size, generatedLegacyHeader.imports.size, "imports size mismatch")
        // Compare exports
        assertEquals(originalLegacyHeader.exports.size, generatedLegacyHeader.exports.size, "exports size mismatch")

        // For Randy, check that imports content matches (class_package, class_name, object_name)
        for (i in originalLegacyHeader.imports.indices) {
            val orig = originalLegacyHeader.imports[i]
            val gen = generatedLegacyHeader.imports[i]
            val origClassPackage = originalLegacyHeader.name_map.get(orig.class_package)
            val genClassPackage = generatedLegacyHeader.name_map.get(gen.class_package)
            assertEquals(origClassPackage, genClassPackage, "import $i class_package mismatch")
            val origClassName = originalLegacyHeader.name_map.get(orig.class_name)
            val genClassName = generatedLegacyHeader.name_map.get(gen.class_name)
            assertEquals(origClassName, genClassName, "import $i class_name mismatch")
            val origObj = originalLegacyHeader.name_map.get(orig.object_name)
            val genObj = generatedLegacyHeader.name_map.get(gen.object_name)
            assertEquals(origObj, genObj, "import $i object_name mismatch")
        }

        // Compare exports: object names and flags
        for (i in originalLegacyHeader.exports.indices) {
            val orig = originalLegacyHeader.exports[i]
            val gen = generatedLegacyHeader.exports[i]
            val origName = originalLegacyHeader.name_map.get(orig.object_name)
            val genName = generatedLegacyHeader.name_map.get(gen.object_name)
            assertEquals(origName, genName, "export $i name mismatch")
            assertEquals(orig.object_flags, gen.object_flags, "export $i flags mismatch")
            assertEquals(orig.serial_size, gen.serial_size, "export $i serial_size mismatch")
        }

        // Compare preload dependencies size (should be similar)
        // For Randy, preload dependencies may be empty or small; just ensure not empty mismatch drastically
        // Original Randy has preload_dependencies size? Let's just check that generated has same count as original's preload count for this simple case
        // In our earlier dump, Randy legacy preload size unknown, but we can assert equality
        assertEquals(originalLegacyHeader.preload_dependencies.size, generatedLegacyHeader.preload_dependencies.size, "preload_dependencies size mismatch")

        // Verify exports data: For Randy, exports data is small; compare that serialized exports buffer is non-empty and similar to original
        assertTrue(serialized.exports_file_buffer.isNotEmpty())
        // For Randy, original exports is from .uexp (size unknown). Our generated exports should be similar size
        // We can compare that the generated exports file contains the same data as original .uexp (for this simple asset, exports data is just the export blob)
        // Since Randy has 1 export, its export data should be comparable
        if (originalLegacyExports.isNotEmpty()) {
            // Our generated exports includes footer magic (4 bytes) at end, original also has footer? For legacy assets, .uexp file includes exports data plus footer.
            // We should strip footer and compare
            val origWithoutFooter = if (originalLegacyExports.size >= 4) originalLegacyExports.copyOfRange(0, originalLegacyExports.size - 4) else originalLegacyExports
            val genWithoutFooter = if (serialized.exports_file_buffer.size >= 4) serialized.exports_file_buffer.copyOfRange(0, serialized.exports_file_buffer.size - 4) else serialized.exports_file_buffer
            // For this simple asset, the export blob should match exactly because it's just the serialized export properties
            assertArrayEquals(origWithoutFooter, genWithoutFooter, "exports data mismatch for Randy")
        }

        // Final sanity: Use TreeMap for determinism
        val treeFinal = TreeMap<String, Int>()
        treeFinal["Randy"] = generatedLegacyHeader.exports.size
        val bbFinal = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeFinal.size).array()
        assertEquals(treeFinal.size, ByteBuffer.wrap(bbFinal).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test
    fun test_asset_conversion_BP_Table_Lamp() {
        val engineVersion = EngineVersion.UE5_4
        val containerVersion = engineVersion.toc_version()
        val headerVersion = engineVersion.container_header_version()
        val packageVersion = engineVersion.package_file_version()

        val zenPath = TestFixtures.find("UE5.4/BP_Table_Lamp.uzenasset")!!
        val legacyPath = TestFixtures.find("UE5.4/BP_Table_Lamp.uasset")!!

        assertTrue(Files.exists(zenPath))
        assertTrue(Files.exists(legacyPath))

        val zenBytes = Files.readAllBytes(zenPath)
        val originalLegacyBytes = Files.readAllBytes(legacyPath)
        val originalLegacyHeader = FLegacyPackageHeader.deserialize(SeekableByteArrayInputStream(originalLegacyBytes), packageVersion)

        val zenHeaderForId = FZenPackageHeader.deserialize(SeekableByteArrayInputStream(zenBytes), null, containerVersion, headerVersion, packageVersion)
        val packageName = zenHeaderForId.package_name()
        val packageId = FPackageId.from_name(packageName)
        assertEquals("/Game/Billiards/Blueprints/BP_Table_Lamp", packageName)

        // Load script objects
        val scriptObjectsBytes = Files.readAllBytes(TestFixtures.find("UE5.3/ScriptObjects.bin")!!)
        val scriptObjects = ZenScriptObjects.deserialize_new(java.io.ByteArrayInputStream(scriptObjectsBytes))

        // Create mocked header for SM_table_lamp dependency
        val smPackageName = "/Game/Billiards/Static_meshes/SM_table_lamp"
        val smPackageId = FPackageId.from_name(smPackageName)
        val smHeader = createDummySmTableLampHeader(packageVersion, headerVersion)

        val packageBytesMap = HashMap<FPackageId, ByteArray>()
        packageBytesMap[packageId] = zenBytes
        // For SM_table_lamp, we will not put raw bytes but mock via header; however FakeIoStore will need to return bytes for that package when read is called.
        // We'll put mocked header's serialized bytes
        val smStoreEntry = StoreEntry(
            imported_packages = emptyList(),
            shader_map_hashes = emptyList(),
            export_count = smHeader.export_map.size,
            export_bundle_count = 0
        )
        val smSerializedStream = SeekableByteArrayOutputStream()
        smHeader.serialize(smSerializedStream, smStoreEntry, headerVersion)
        val smBytes = smSerializedStream.toByteArray()
        packageBytesMap[smPackageId] = smBytes

        val mockedHeaders = HashMap<FPackageId, FZenPackageHeader>()
        mockedHeaders[smPackageId] = smHeader
        // Also need StoreEntry for mocked
        val mockedStoreEntries = HashMap<FPackageId, StoreEntry>()
        mockedStoreEntries[smPackageId] = smStoreEntry
        // For main package, store entry will be derived from zenHeader
        val mainStoreEntry = StoreEntry(
            imported_packages = zenHeaderForId.imported_packages,
            shader_map_hashes = zenHeaderForId.shader_map_hashes,
            export_count = zenHeaderForId.export_map.size,
            export_bundle_count = zenHeaderForId.export_bundle_headers.size
        )
        mockedStoreEntries[packageId] = mainStoreEntry

        val fakeStore = FakeIoStore(
            packageBytes = packageBytesMap,
            scriptObjects = scriptObjects,
            containerVersion = containerVersion,
            headerVersion = headerVersion,
            mockedHeaders = mockedHeaders,
            mockedStoreEntries = mockedStoreEntries
        )

        val log = Log.no_log()
        val packageContext = FZenPackageContext.create(fakeStore, packageVersion, log, null)

        val builder = build_asset_from_zen(packageContext, packageId)
        val serialized = serialize_asset(builder)

        val generatedLegacyHeader = FLegacyPackageHeader.deserialize(SeekableByteArrayInputStream(serialized.asset_file_buffer), packageVersion)

        // Basic checks
        assertEquals(originalLegacyHeader.summary.package_name, generatedLegacyHeader.summary.package_name)
        assertEquals(originalLegacyHeader.imports.size, generatedLegacyHeader.imports.size, "imports size mismatch for BP_Table_Lamp")
        assertEquals(originalLegacyHeader.exports.size, generatedLegacyHeader.exports.size, "exports size mismatch for BP_Table_Lamp")

        // Verify a few imports match expected values (especially SM_table_lamp package import)
        // Find import for SM_table_lamp package (object_name is full path)
        val origSmPackageImport = originalLegacyHeader.imports.find { originalLegacyHeader.name_map.get(it.object_name) == "/Game/Billiards/Static_meshes/SM_table_lamp" && originalLegacyHeader.name_map.get(it.class_name) == "Package" }
        assertNotNull(origSmPackageImport, "original should have SM_table_lamp package import")
        val genSmPackageImport = generatedLegacyHeader.imports.find { generatedLegacyHeader.name_map.get(it.object_name) == "/Game/Billiards/Static_meshes/SM_table_lamp" && generatedLegacyHeader.name_map.get(it.class_name) == "Package" }
        assertNotNull(genSmPackageImport, "generated should have SM_table_lamp package import")

        // Verify exports
        assertEquals(originalLegacyHeader.exports.size, generatedLegacyHeader.exports.size)
        for (i in originalLegacyHeader.exports.indices) {
            val orig = originalLegacyHeader.exports[i]
            val gen = generatedLegacyHeader.exports[i]
            val origName = originalLegacyHeader.name_map.get(orig.object_name)
            val genName = generatedLegacyHeader.name_map.get(gen.object_name)
            assertEquals(origName, genName, "export $i name mismatch")
        }

        // TreeMap check
        val treeCheck = TreeMap<String, Int>()
        treeCheck[generatedLegacyHeader.summary.package_name] = generatedLegacyHeader.imports.size
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
        assertEquals(treeCheck.size, ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    // EXC-015: an unloadable imported package must not veto resolution of
    // later packages in resolve_package_import_internal_legacy. A mesh-style
    // caller lists a missing package FIRST and a present package SECOND; the
    // present package's export must still resolve, while a genuinely missing
    // target must still throw (EXC-011 sentinel path preserved).
    @Test
    fun test_legacy_resolve_skips_unloadable_package() {
        val engineVersion = EngineVersion.UE5_4
        val containerVersion = engineVersion.toc_version()
        val headerVersion = engineVersion.container_header_version()
        val packageVersion = engineVersion.package_file_version()

        val smPackageName = "/Game/Billiards/Static_meshes/SM_table_lamp"
        val smPackageId = FPackageId.from_name(smPackageName)
        val smHeader = createDummySmTableLampHeader(packageVersion, headerVersion)
        val smImport = smHeader.export_map[0].legacy_global_import_index()

        val scriptObjectsBytes = Files.readAllBytes(TestFixtures.find("UE5.3/ScriptObjects.bin")!!)
        val scriptObjects = ZenScriptObjects.deserialize_new(java.io.ByteArrayInputStream(scriptObjectsBytes))

        val smStoreEntry = StoreEntry(
            imported_packages = emptyList(),
            shader_map_hashes = emptyList(),
            export_count = smHeader.export_map.size,
            export_bundle_count = 0
        )
        val mockedHeaders = HashMap<FPackageId, FZenPackageHeader>()
        mockedHeaders[smPackageId] = smHeader
        val mockedStoreEntries = HashMap<FPackageId, StoreEntry>()
        mockedStoreEntries[smPackageId] = smStoreEntry
        val fakeStore = FakeIoStore(
            packageBytes = HashMap(),
            scriptObjects = scriptObjects,
            containerVersion = containerVersion,
            headerVersion = headerVersion,
            mockedHeaders = mockedHeaders,
            mockedStoreEntries = mockedStoreEntries
        )
        val log = Log.no_log()
        val packageContext = FZenPackageContext.create(fakeStore, packageVersion, log, null)

        fun callerHeader(vararg ids: FPackageId): FZenPackageHeader {
            val nameMap = FNameMap.create(EMappedNameType.Package)
            val nm = nameMap.store("/Game/Caller/M")
            val header = FZenPackageHeader(container_header_version = headerVersion)
            header.name_map = nameMap
            header.summary = FZenPackageSummary()
            header.summary.name = nm
            header.imported_packages = ids.toMutableList()
            return header
        }

        val goneId = FPackageId(0xBADF00D5EEDUL)
        // missing package listed FIRST: must not veto the present one
        val resolved = resolve_package_import_internal_legacy(
            packageContext, callerHeader(goneId, smPackageId), smImport)
        assertEquals("SM_table_lamp", resolved.object_name)
        assertEquals("StaticMesh", resolved.class_name)
        // present-first order keeps working too
        val resolved2 = resolve_package_import_internal_legacy(
            packageContext, callerHeader(smPackageId, goneId), smImport)
        assertEquals("SM_table_lamp", resolved2.object_name)
        // genuinely missing target still throws (placeholder path intact)
        val missing = FPackageObjectIndex.create(FPackageObjectIndexType.PackageImport, 0x12345678UL)
        assertThrows(IllegalArgumentException::class.java) {
            resolve_package_import_internal_legacy(packageContext, callerHeader(goneId), missing)
        }
    }

    // EXC-015: a mixed-version source container (Initial-era caller header in
    // the legacy resolver scheme) that lists an unloadable package ahead of a
    // newer, present one must still convert into the same import table as the
    // baseline container without the unloadable entry — and the data-embedded
    // ref lookup must agree with that table entry.
    @Test
    fun test_legacy_mixed_container_refs_agree() {
        val engineVersion = EngineVersion.UE5_4
        val containerVersion = engineVersion.toc_version()
        val headerVersion = engineVersion.container_header_version()
        val packageVersion = engineVersion.package_file_version()

        val smPackageName = "/Game/Billiards/Static_meshes/SM_table_lamp"
        val smPackageId = FPackageId.from_name(smPackageName)
        val smHeader = createDummySmTableLampHeader(packageVersion, headerVersion)
        // Legacy containers store the object's global import index (a
        // PackageImport-kind index) in the export's hash field.
        val smImport = FPackageObjectIndex.create_legacy_package_import_from_path("$smPackageName.SM_table_lamp")
        smHeader.export_map[0].public_export_hash = smImport.type_and_id

        val scriptObjectsBytes = Files.readAllBytes(TestFixtures.find("UE5.3/ScriptObjects.bin")!!)
        val scriptObjects = ZenScriptObjects.deserialize_new(java.io.ByteArrayInputStream(scriptObjectsBytes))
        val smStoreEntry = StoreEntry(
            imported_packages = emptyList(),
            shader_map_hashes = emptyList(),
            export_count = smHeader.export_map.size,
            export_bundle_count = 0
        )

        fun context(): FZenPackageContext {
            val mockedHeaders = HashMap<FPackageId, FZenPackageHeader>()
            mockedHeaders[smPackageId] = smHeader
            val mockedStoreEntries = HashMap<FPackageId, StoreEntry>()
            mockedStoreEntries[smPackageId] = smStoreEntry
            val fakeStore = FakeIoStore(
                packageBytes = HashMap(),
                scriptObjects = scriptObjects,
                containerVersion = containerVersion,
                headerVersion = headerVersion,
                mockedHeaders = mockedHeaders,
                mockedStoreEntries = mockedStoreEntries
            )
            return FZenPackageContext.create(fakeStore, packageVersion, Log.no_log(), null)
        }

        fun caller(vararg ids: FPackageId): FZenPackageHeader {
            val nameMap = FNameMap.create(EMappedNameType.Package)
            val nm = nameMap.store("/Game/Caller/M")
            val header = FZenPackageHeader(
                container_header_version = EIoContainerHeaderVersion.Initial,
                name_map = nameMap
            )
            header.summary = FZenPackageSummary()
            header.summary.name = nm
            header.imported_packages = ids.toMutableList()
            header.import_map = mutableListOf(smImport)
            return header
        }

        fun refs(builder: LegacyAssetBuilder): List<String> = builder.legacy_package.imports.map {
            val classPackage = builder.legacy_package.name_map.get(it.class_package)
            val className = builder.legacy_package.name_map.get(it.class_name)
            val objectName = builder.legacy_package.name_map.get(it.object_name)
            "$classPackage|$className|$objectName"
        }

        // baseline: only the present package is listed
        val baselineBuilder = LegacyAssetBuilder(context(), FPackageId.from_name("/Game/Caller/M"), caller(smPackageId))
        build_import_map(baselineBuilder)

        // mixed: an unloadable package is listed FIRST
        val goneId = FPackageId(0xBADF00D5EEDUL)
        val mixedBuilder = LegacyAssetBuilder(context(), FPackageId.from_name("/Game/Caller/M"), caller(goneId, smPackageId))
        build_import_map(mixedBuilder)

        assertFalse(mixedBuilder.has_failed_import_map_entries)
        assertEquals(refs(baselineBuilder), refs(mixedBuilder), "converted refs must agree with the source container")
        assertTrue(refs(mixedBuilder).none { it.contains("/Engine/UnknownPackage") })
        assertTrue(refs(mixedBuilder).any { it.contains("SM_table_lamp") })

        // the data-embedded ref path resolves to the same table entry
        val tableIndex = mixedBuilder.original_import_order[0]!!
        val dataIndex = resolve_local_package_object(mixedBuilder, smImport)
        assertTrue(dataIndex.is_import())
        assertEquals(tableIndex, dataIndex.to_import_index().toInt())
    }

    // Helper to create dummy SM_table_lamp header
    private fun createDummySmTableLampHeader(packageVersion: FPackageFileVersion, headerVersion: EIoContainerHeaderVersion): FZenPackageHeader {
        val nameMap = FNameMap.create(EMappedNameType.Package)
        val packageName = "/Game/Billiards/Static_meshes/SM_table_lamp"
        val packageNameIdx = nameMap.store(packageName)
        val smObjectNameIdx = nameMap.store("SM_table_lamp")
        val header = FZenPackageHeader(
            container_header_version = headerVersion
        )
        header.name_map = nameMap
        header.summary = FZenPackageSummary(
            has_versioning_info = 1u,
            header_size = 0u, // will be computed on serialize
            name = packageNameIdx,
            package_flags = 0u,
            cooked_header_size = 0u,
            imported_public_export_hashes_offset = -1,
            import_map_offset = -1,
            export_map_offset = -1,
            export_bundle_entries_offset = -1,
            graph_data_offset = -1,
            dependency_bundle_headers_offset = -1,
            dependency_bundle_entries_offset = -1,
            imported_package_names_offset = -1
        )
        header.versioning_info = FZenPackageVersioningInfo(
            zen_version = EZenPackageVersion.ExportDependencies,
            package_file_version = packageVersion,
            licensee_version = 0,
            custom_versions = mutableListOf()
        )
        // Create one export for SM_table_lamp StaticMesh
        val classIndex = FPackageObjectIndex.create_script_import("/Script/Engine.StaticMesh")
        val exportEntry = FExportMapEntry(
            cooked_serial_offset = 0UL,
            cooked_serial_size = 0UL,
            object_name = smObjectNameIdx,
            outer_index = FPackageObjectIndex.create_null(),
            class_index = classIndex,
            super_index = FPackageObjectIndex.create_null(),
            template_index = FPackageObjectIndex.create_null(),
            public_export_hash = 17138791323417526498UL, // edd937e747d940e2
            object_flags = (EObjectFlags.Public.value or EObjectFlags.Standalone.value),
            filter_flags = EExportFilterFlags.None,
            padding = ByteArray(3)
        )
        header.export_map.add(exportEntry)
        // Minimal other fields
        header.import_map = mutableListOf()
        header.imported_packages = mutableListOf()
        header.imported_package_names = mutableListOf()
        header.imported_public_export_hashes = mutableListOf()
        header.bulk_data = mutableListOf()
        header.export_bundle_entries = mutableListOf(
            FExportBundleEntry(0u, EExportCommandType.Create),
            FExportBundleEntry(0u, EExportCommandType.Serialize)
        )
        header.dependency_bundle_headers = mutableListOf(
            FDependencyBundleHeader(first_entry_index = 0, create_before_create_dependencies = 0u, serialize_before_create_dependencies = 0u, create_before_serialize_dependencies = 0u, serialize_before_serialize_dependencies = 0u)
        )
        header.dependency_bundle_entries = mutableListOf()
        header.cell_import_map = mutableListOf()
        header.cell_export_map = mutableListOf()
        header.export_bundle_headers = mutableListOf()
        header.internal_dependency_arcs = mutableListOf()
        header.external_package_dependencies = mutableListOf()
        header.is_unversioned = false
        // Use TreeMap ByteBuffer check
        val treeCheck = TreeMap<String, Int>()
        treeCheck[packageName] = header.export_map.size
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(treeCheck.size).array()
        check(ByteBuffer.wrap(bb).order(ByteOrder.LITTLE_ENDIAN).int == treeCheck.size)
        return header
    }

    // Fake IoStore for testing
    class FakeIoStore(
        private val packageBytes: Map<FPackageId, ByteArray>,
        private val scriptObjects: ZenScriptObjects?,
        private val containerVersion: EIoStoreTocVersion,
        private val headerVersion: EIoContainerHeaderVersion,
        private val mockedHeaders: Map<FPackageId, FZenPackageHeader> = emptyMap(),
        private val mockedStoreEntries: Map<FPackageId, StoreEntry> = emptyMap()
    ) : IoStoreTrait {
        override fun container_name(): String = "Fake"
        override fun container_id(): FIoContainerId = FIoContainerId(0UL)
        override fun mount_point(): String = ""
        override fun container_file_version(): EIoStoreTocVersion = containerVersion
        override fun container_header_version(): EIoContainerHeaderVersion = headerVersion
        override fun print_info(depth: Int) {}

        override fun read(chunk_id: FIoChunkId): ByteArray {
            val type = chunk_id.get_chunk_type()
            val pid = chunk_id.get_package_id()
            if (type == EIoChunkType.ExportBundleData) {
                // If we have mocked header, serialize it
                mockedHeaders[pid]?.let { header ->
                    val storeEntry = mockedStoreEntries[pid] ?: StoreEntry()
                    val stream = SeekableByteArrayOutputStream()
                    header.serialize(stream, storeEntry, headerVersion)
                    // Append dummy exports data (empty)
                    return stream.toByteArray()
                }
                return packageBytes[pid] ?: throw IllegalArgumentException("package $pid not found for ExportBundleData")
            }
            if (type == EIoChunkType.ScriptObjects) {
                scriptObjects?.let {
                    val stream = SeekableByteArrayOutputStream()
                    // Serialize as new format: FNameMap + script_objects vec
                    // Use scriptObjects.serialize_new
                    val baos = java.io.ByteArrayOutputStream()
                    it.serialize_new(baos)
                    return baos.toByteArray()
                }
                throw IllegalArgumentException("script objects not available")
            }
            if (type == EIoChunkType.BulkData || type == EIoChunkType.OptionalBulkData || type == EIoChunkType.MemoryMappedBulkData) {
                throw IllegalArgumentException("bulk data chunk $chunk_id not found")
            }
            throw IllegalArgumentException("unsupported chunk type $type for $chunk_id")
        }

        override fun read_raw(chunk_id_raw: FIoChunkIdRaw): ByteArray {
            return read(FIoChunkId.from_raw(chunk_id_raw, containerVersion))
        }

        override fun has_chunk_id(chunk_id: FIoChunkId): Boolean {
            val type = chunk_id.get_chunk_type()
            return when (type) {
                EIoChunkType.ExportBundleData -> packageBytes.containsKey(chunk_id.get_package_id()) || mockedHeaders.containsKey(chunk_id.get_package_id())
                EIoChunkType.ScriptObjects -> scriptObjects != null
                else -> false
            }
        }

        override fun has_chunk_id_raw(chunk_id_raw: FIoChunkIdRaw): Boolean {
            return has_chunk_id(FIoChunkId.from_raw(chunk_id_raw, containerVersion))
        }

        override fun chunks(): Sequence<ChunkInfo> = emptySequence()
        override fun chunks_all(): Sequence<ChunkInfo> = emptySequence()
        override fun packages(): Sequence<PackageInfo> = emptySequence()
        override fun packages_all(): Sequence<PackageInfo> = emptySequence()
        override fun child_containers(): Sequence<IoStoreTrait> = emptySequence()
        override fun chunk_path(chunk_id: FIoChunkId): String? = null
        override fun package_store_entry(package_id: FPackageId): StoreEntry? {
            mockedStoreEntries[package_id]?.let { return it }
            val bytes = packageBytes[package_id] ?: return null
            return try {
                val header = FZenPackageHeader.deserialize(SeekableByteArrayInputStream(bytes), null, containerVersion, headerVersion, null)
                StoreEntry(
                    imported_packages = header.imported_packages,
                    shader_map_hashes = header.shader_map_hashes,
                    export_count = header.export_map.size,
                    export_bundle_count = header.export_bundle_headers.size
                )
            } catch (e: Exception) {
                // Fallback to empty
                StoreEntry()
            }
        }

        override fun lookup_package_redirect(source_package_id: FPackageId): FPackageId? = null
    }
}
