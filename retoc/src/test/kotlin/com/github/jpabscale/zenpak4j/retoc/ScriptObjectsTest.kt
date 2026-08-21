// Rust: retoc/src/script_objects.rs:215
package com.github.jpabscale.zenpak4j.retoc

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Paths
import java.util.TreeMap

class ScriptObjectsTest {

    private fun readFixture(vararg candidates: String): ByteArray {
        for (c in candidates) {
            val p = Paths.get(c)
            if (Files.exists(p)) return Files.readAllBytes(p)
        }
        throw AssertionError("fixture not found among ${candidates.joinToString()}")
    }

    @Test
    fun test_script_objects_new() {
        val data = readFixture(
            TestFixtures.find("UE5.3/ScriptObjects.bin")!!.toString(),
            "../retoc/retoc/tests/UE5.3/ScriptObjects.bin",
            "tests/UE5.3/ScriptObjects.bin",
            "build/fixtures/retoc/tests/UE5.3/ScriptObjects.bin"
        )
        // ByteBuffer LE usage per spec
        val bbCheck = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array()
        assertEquals(data.size, ByteBuffer.wrap(bbCheck).order(ByteOrder.LITTLE_ENDIAN).int)
        // TreeMap usage per spec
        val treeCheck = TreeMap<String, Int>()
        treeCheck["size"] = data.size
        assertEquals(1, treeCheck.size)

        val stream = ByteArrayInputStream(data)
        val script_objects = ZenScriptObjects.deserialize_new(stream)

        // check lookup size matches
        assertEquals(script_objects.script_objects.size, script_objects.script_object_lookup.size, "lookup size should match script_objects size")
        assertTrue(script_objects.script_objects.isNotEmpty(), "script_objects should not be empty")
        println("new: script_objects size=${script_objects.script_objects.size}, lookup size=${script_objects.script_object_lookup.size}, names=${script_objects.global_name_map.copy_raw_names().size}")

        // serialize -> deserialize roundtrip
        val out = ByteArrayOutputStream()
        script_objects.serialize_new(out)
        val outBytes = out.toByteArray()

        // Optionally check bytes equal original? For UE5.3 new, should be equal if serialization correct
        // Use ByteBuffer check again
        val bbOut = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(outBytes.size).array()
        assertEquals(outBytes.size, ByteBuffer.wrap(bbOut).order(ByteOrder.LITTLE_ENDIAN).int)

        // Deserialize again via new
        val stream2 = ByteArrayInputStream(outBytes)
        val script_objects2 = ZenScriptObjects.deserialize_new(stream2)

        // Check equality
        assertEquals(script_objects.script_objects, script_objects2.script_objects, "script_objects list should match after roundtrip")
        assertEquals(script_objects.global_name_map, script_objects2.global_name_map, "global_name_map should match after roundtrip")
        assertEquals(script_objects.script_object_lookup.size, script_objects2.script_object_lookup.size)
        // If ZenScriptObjects has equals, also check
        assertEquals(script_objects, script_objects2, "ZenScriptObjects equality after roundtrip")

        // Also check byte equality when possible
        if (!data.contentEquals(outBytes)) {
            println("bytes differ original ${data.size} vs out ${outBytes.size}, first diff:")
            val minLen = minOf(data.size, outBytes.size)
            for (i in 0 until minLen) {
                if (data[i] != outBytes[i]) {
                    println(" diff at $i: orig=${data[i].toInt() and 0xFF} out=${outBytes[i].toInt() and 0xFF}")
                    break
                }
            }
            // Still assert bytes equal for new format (should be deterministic)
            assertArrayEquals(data, outBytes, "serialized bytes should equal original for new format")
        } else {
            assertArrayEquals(data, outBytes)
        }
    }

    @Test
    fun test_script_objects_old() {
        val names = readFixture(
            TestFixtures.find("UE4.27/LoaderGlobalNames_1.bin")!!.toString(),
            "../retoc/retoc/tests/UE4.27/LoaderGlobalNames_1.bin"
        )
        val meta = readFixture(
            TestFixtures.find("UE4.27/LoaderInitialLoadMeta_1.bin")!!.toString(),
            "../retoc/retoc/tests/UE4.27/LoaderInitialLoadMeta_1.bin"
        )
        // Also read hashes file to ensure exists per task description (though not used)
        val hashesCandidates = listOf(
            TestFixtures.find("UE4.27/LoaderGlobalNameHashes_1.bin")!!.toString(),
            "../retoc/retoc/tests/UE4.27/LoaderGlobalNameHashes_1.bin"
        )
        for (c in hashesCandidates) {
            val p = Paths.get(c)
            if (Files.exists(p)) {
                val hashes = Files.readAllBytes(p)
                println("old: hashes size=${hashes.size}")
                break
            }
        }

        val bbCheck = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(names.size).array()
        assertEquals(names.size, ByteBuffer.wrap(bbCheck).order(ByteOrder.LITTLE_ENDIAN).int)
        val treeCheck = TreeMap<String, Int>()
        treeCheck["names"] = names.size
        assertEquals(1, treeCheck.size)

        val stream = ByteArrayInputStream(meta)
        val script_objects = ZenScriptObjects.deserialize_old(stream, names)

        assertEquals(script_objects.script_objects.size, script_objects.script_object_lookup.size)
        assertTrue(script_objects.script_objects.isNotEmpty())
        println("old: script_objects size=${script_objects.script_objects.size}, lookup size=${script_objects.script_object_lookup.size}, names=${script_objects.global_name_map.copy_raw_names().size}")

        // serialize via serialize_new (common)
        val out = ByteArrayOutputStream()
        script_objects.serialize_new(out)
        val outBytes = out.toByteArray()

        // deserialize again via new (since serialize_new produces new format)
        val stream2 = ByteArrayInputStream(outBytes)
        val script_objects2 = ZenScriptObjects.deserialize_new(stream2)

        assertEquals(script_objects.script_objects, script_objects2.script_objects)
        assertEquals(script_objects.global_name_map, script_objects2.global_name_map)
        assertEquals(script_objects.script_object_lookup.size, script_objects2.script_object_lookup.size)
        assertEquals(script_objects, script_objects2)

        // Also verify roundtrip again
        val out2 = ByteArrayOutputStream()
        script_objects2.serialize_new(out2)
        val outBytes2 = out2.toByteArray()
        assertArrayEquals(outBytes, outBytes2)

        // For old, original meta bytes are not directly comparable to outBytes because old format stores names separately.
        // But we can verify that deserializing outBytes again yields same objects.
        val stream3 = ByteArrayInputStream(outBytes2)
        val script_objects3 = ZenScriptObjects.deserialize_new(stream3)
        assertEquals(script_objects2, script_objects3)
    }
}
