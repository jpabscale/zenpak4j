package com.github.jpabscale.zenpak4j.retoc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.io.File

class AssetRegistryTest {

    private fun readFixture(vararg candidates: String): ByteArray {
        for (c in candidates) {
            val p = Paths.get(c)
            if (Files.exists(p)) {
                return Files.readAllBytes(p)
            }
        }
        throw AssertionError("fixture not found among ${candidates.joinToString()}")
    }

    private fun test_asset_registry_roundtrip(path: String) {
        val input = Files.readAllBytes(TestFixtures.find(path.substringAfter("tests/"))!!)
        // deserialize
        val registry = AssetRegistry.deserialize_static(ByteArrayInputStream(input))
        assertTrue(registry.asset_data.isNotEmpty(), "Asset data should not be empty for $path")

        // serialize
        val out = ByteArrayOutputStream()
        registry.serialize(out)
        val output = out.toByteArray()

        // deserialize again
        val registry2 = AssetRegistry.deserialize_static(ByteArrayInputStream(output))

        assertEquals(registry.version, registry2.version, "version mismatch $path")
        assertEquals(registry.registry_version, registry2.registry_version, "registry_version mismatch $path")
        assertEquals(registry.asset_data.size, registry2.asset_data.size, "asset_data size mismatch $path")

        for ((a, b) in registry.asset_data.zip(registry2.asset_data)) {
            assertEquals(a, b, "AssetData mismatch $path")
        }

        assertEquals(registry, registry2, "registry equality failed for $path")
        // also check byte equality as Rust does
        // For UE4.22, byte equality should hold if we perfectly roundtrip (including name table ordering)
        // Print helpful info if mismatch
        if (!input.contentEquals(output)) {
            println("byte mismatch for $path: input ${input.size} vs output ${output.size}")
            val minLen = minOf(input.size, output.size)
            for (i in 0 until minLen) {
                if (input[i] != output[i]) {
                    println(" first diff at $i: input=${input[i].toInt() and 0xFF} output=${output[i].toInt() and 0xFF}")
                    break
                }
            }
            // also show header differences
            println("registry version ${registry.registry_version} vs ${registry2.registry_version}")
            println("header ${registry.header} vs ${registry2.header}")
            println("store pairs ${registry.store.pairs.size} vs ${registry2.store.pairs.size}")
            println("store nbl_names ${registry.store.nbl_names.size} vs ${registry2.store.nbl_names.size}")
        }
        assertTrue(input.contentEquals(output), "serialized bytes should equal original for $path (input ${input.size} vs output ${output.size})")
    }

    @Test
    fun test_export_path_roundtrip() {
        val registry = AssetRegistry.new(FAssetRegistryVersion.ClassPaths)
        registry.store.nbl_export_paths.add(ExportPath(
            object_path = "/Game/Test",
            package_path = "/Game",
            asset_class = AssetClassPath.TopLevelAssetPath(FTopLevelAssetPath(
                package_name = "/Script/Engine",
                asset_name = "Blueprint"
            ))
        ))
        val out = ByteArrayOutputStream()
        registry.serialize(out)
        val bytes = out.toByteArray()
        val decoded = AssetRegistry.deserialize_static(ByteArrayInputStream(bytes))
        assertEquals(registry, decoded)
    }

    @Test
    fun test_asset_data_roundtrip() {
        val registry = AssetRegistry.new(FAssetRegistryVersion.FixedTags)
        registry.asset_data.add(AssetData(
            object_path = "/Game/MyAsset.MyAsset",
            package_path = "/Game",
            asset_class = "Blueprint",
            package_name = "/Game/MyAsset",
            asset_name = "MyAsset",
            tags = MapHandle(has_numberless_keys = true, num = 0u, pair_begin = 0u),
            legacy_tags = mutableListOf(),
            bundles = mutableListOf(),
            chunk_ids = mutableListOf(1u, 2u, 3u),
            flags = 0x42u
        ))
        val out = ByteArrayOutputStream()
        registry.serialize(out)
        val decoded = AssetRegistry.deserialize_static(ByteArrayInputStream(out.toByteArray()))
        assertEquals(registry, decoded)
    }

    @Test
    fun test_fname_with_number() {
        val registry = AssetRegistry.new(FAssetRegistryVersion.FixedTags)
        registry.asset_data.add(AssetData(
            object_path = "/Game/Test_0",
            package_path = "/Game",
            asset_class = "StaticMesh_5",
            package_name = "/Game/Test",
            asset_name = "Test_0",
            tags = MapHandle(has_numberless_keys = true, num = 0u, pair_begin = 0u),
            legacy_tags = mutableListOf(),
            bundles = mutableListOf(),
            chunk_ids = mutableListOf(),
            flags = 0u
        ))
        val out = ByteArrayOutputStream()
        registry.serialize(out)
        val decoded = AssetRegistry.deserialize_static(ByteArrayInputStream(out.toByteArray()))
        assertEquals(registry.asset_data[0].object_path, decoded.asset_data[0].object_path)
        assertEquals(registry.asset_data[0].asset_class, decoded.asset_data[0].asset_class)
        assertEquals(registry.asset_data[0].asset_name, decoded.asset_data[0].asset_name)
    }

    @Test
    fun test_parse_ue422_asset_registry() {
        test_asset_registry_roundtrip("tests/UE4.22/AssetRegistry.bin")
    }

    @Test
    fun test_parse_ue427_asset_registry() {
        test_asset_registry_roundtrip("tests/UE4.27/AssetRegistry.bin")
    }

    @Test
    fun test_parse_ue55_asset_registry() {
        test_asset_registry_roundtrip("tests/UE5.5/AssetRegistry.bin")
    }

    @Test
    fun test_parse_ue56_asset_registry() {
        test_asset_registry_roundtrip("tests/UE5.6/AssetRegistry.bin")
    }

    @Test
    fun test_asset_registry_field_access() {
        val data = Files.readAllBytes(TestFixtures.find("UE4.27/AssetRegistry.bin"))
        val registry = AssetRegistry.deserialize_static(ByteArrayInputStream(data))
        for (asset in registry.asset_data) {
            assertTrue(asset.object_path.isNotEmpty(), "object_path should not be empty")
            assertTrue(asset.package_path.isNotEmpty(), "package_path should not be empty")
            assertTrue(asset.asset_class.isNotEmpty(), "asset_class should not be empty")
        }
        // also check store fields as mentioned in task
        assertTrue(registry.store.pairs.isNotEmpty() || registry.store.ansi_strings.isNotEmpty() || registry.store.names.isNotEmpty() || registry.store.texts.isNotEmpty(), "store should have some data")
    }
}
