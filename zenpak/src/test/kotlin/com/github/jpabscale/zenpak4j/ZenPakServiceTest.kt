package com.github.jpabscale.zenpak4j

import com.github.jpabscale.zenpak4j.repak.Compression
import com.github.jpabscale.zenpak4j.repak.RepakError
import com.github.jpabscale.zenpak4j.repak.Version
import com.github.jpabscale.zenpak4j.retoc.Config
import com.github.jpabscale.zenpak4j.retoc.EIoChunkType
import com.github.jpabscale.zenpak4j.retoc.EIoStoreTocVersion
import com.github.jpabscale.zenpak4j.retoc.EngineVersion
import com.github.jpabscale.zenpak4j.retoc.FIoChunkId
import com.github.jpabscale.zenpak4j.retoc.open
import com.github.jpabscale.zenpak4j.retoc_actions.ActionGenScriptObjects
import com.github.jpabscale.zenpak4j.retoc_actions.action_gen_script_objects
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * High-coverage tests for the in-process ZenPakService, exercising the shared :actions layer
 * (the same code the CLIs run) against the upstream repak/retoc test fixtures:
 *
 * - repak: the UnrealPak-generated reference packs (v5/v7/v8a/v8b/v9/v11 x compress/encrypt/
 *   encryptindex) from the repak repo, including AES-encrypted variants keyed with crypto.json.
 * - retoc: the loose uasset/uexp/ubulk/shader fixtures (UE5.4/5.5/5.6) for to-zen, passthrough
 *   chunks for unpack, and a gen-script-objects container for the to-legacy happy path.
 */
class ZenPakServiceTest {

    // Upstream retoc/repak test fixtures are NOT committed. Every candidate root is either a
    // trumank/<tool> repo checkout or an extracted tarball; fixtures live at <root>/<tool>/tests.
    // Roots: $ZENPAK4J_FIXTURES, $GITHUB_WORKSPACE/build/fixtures (act/copy-in),
    // <ancestor>/build/fixtures, sibling clones (<ancestor>/../<tool>).
    private fun fixtureTestsDir(tool: String): Path {
        val roots = mutableListOf<Path>()
        System.getenv("ZENPAK4J_FIXTURES")?.takeIf { it.isNotBlank() }?.let { roots.add(Path.of(it)) }
        System.getenv("GITHUB_WORKSPACE")?.takeIf { it.isNotBlank() }?.let {
            roots.add(Path.of(it).resolve("build").resolve("fixtures"))
        }
        var dir: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(6) {
            val d = dir ?: return@repeat
            roots.add(d.resolve("build").resolve("fixtures"))
            roots.add(d.resolve("build").resolve("fixtures").resolve("retoc"))
            roots.add(d.resolve("build").resolve("fixtures").resolve("repak"))
            roots.add(d)
            d.parent?.let { parent ->
                roots.add(parent.resolve("repak"))
                roots.add(parent.resolve("retoc"))
            }
            dir = d.parent
        }
        return roots
            .flatMap { listOf(it.resolve("$tool/tests"), it.resolve("$tool/$tool/tests")) }
            .firstOrNull { Files.isDirectory(it) }
            ?: throw IllegalStateException(
                "$tool fixtures not found — run './gradlew downloadFixtures', set ZENPAK4J_FIXTURES, " +
                    "or place trumank/$tool checkouts next to this repo")
    }

    private fun existingPath(vararg candidates: String): Path =
        candidates.map { Path.of(it) }.firstOrNull { Files.exists(it) }
            ?: throw IllegalStateException("fixture not found, tried: ${candidates.joinToString()}")

    private val retocTests: Path by lazy { fixtureTestsDir("retoc") }
    private val repakPacks: Path by lazy { fixtureTestsDir("repak").resolve("packs") }
    private val repakPackRoot: Path by lazy { fixtureTestsDir("repak").resolve("pack/root") }

    /** The UnrealPak test key from repak's crypto.json (base64 form). */
    private val aesKey = "lNJbw660IOC+kU7cnVQ1oeqrXyhk4J6UAZrCBbcnp94="

    /** Scratch root: ~/Temp/automod when present (user preference), else the system temp. */
    private fun tempDir(prefix: String): Path {
        val base = Path.of(System.getProperty("user.home")).resolve("Temp/automod")
        if (base.toFile().canWrite() || base.toFile().mkdirs()) return Files.createTempDirectory(base, prefix)
        return Files.createTempDirectory(prefix)
    }

    private fun copyDir(src: Path, dst: Path, exclude: (Path) -> Boolean = { false }) {
        Files.walk(src).use { stream ->
            for (p in stream) {
                if (exclude(p)) continue
                val rel = src.relativize(p)
                val target = dst.resolve(rel)
                if (Files.isDirectory(p)) Files.createDirectories(target)
                else {
                    Files.createDirectories(target.parent)
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    private fun assertTreesIdentical(expected: Path, actual: Path) {
        Files.walk(expected).use { stream ->
            for (src in stream.filter { Files.isRegularFile(it) }) {
                val rel = expected.relativize(src).toString()
                val dst = actual.resolve(rel)
                assertTrue(Files.exists(dst), "missing unpacked file $rel")
                assertArrayEquals(Files.readAllBytes(src), Files.readAllBytes(dst), "content mismatch for $rel")
            }
        }
    }

    // ------------------------------------------------------------------
    // repak: own pack/unpack roundtrips through the shared actions
    // ------------------------------------------------------------------

    @Test
    fun test_repak_pack_unpack_roundtrip() {
        val inDir = retocTests.resolve("UE5.5")
        val pak = tempDir("zenpaksvc").resolve("out.pak")
        ZenPakService.repak_pack(inputDir = inDir, outputPak = pak, verbose = false)
        assertTrue(Files.exists(pak) && Files.size(pak) > 0, "pak not written")

        val outDir = tempDir("zenpaksvc_out")
        ZenPakService.repak_unpack(pakFile = pak, outputDir = outDir)
        assertTreesIdentical(inDir, outDir)

        val listed = ZenPakService.repak_list(pak)
        val expectedCount = Files.walk(inDir).use { s -> s.filter { Files.isRegularFile(it) }.count() }
        assertEquals(expectedCount, listed.size.toLong())

        val info = ZenPakService.repak_info(pak)
        assertEquals("../../../", info["mount_point"])
        assertEquals(expectedCount.toString(), info["file_entries"])
        assertEquals("false", info["encrypted_index"])
    }

    @Test
    fun test_repak_pack_versions_and_compression() {
        // small stable input: two fixture files
        val inDir = tempDir("zenpaksvc_in")
        val sub = inDir.resolve("sub"); Files.createDirectories(sub)
        Files.write(inDir.resolve("a.txt"), "alpha".toByteArray())
        Files.write(sub.resolve("b.bin"), ByteArray(4096) { (it % 251).toByte() })

        // Oodle natives have no win-arm64 artifact; drop those cases where unavailable
        val oodleUsable = runCatching { com.github.jpabscale.zenpak4j.oodle_loader.current_platform() }.isSuccess
        val cases = listOf(
            Triple(Version.V8B, null as Compression?, "None"),
            Triple(Version.V9, null as Compression?, "None"),
            Triple(Version.V11, null as Compression?, "None"),
            Triple(Version.V8B, Compression.Oodle, "Oodle"),
            Triple(Version.V11, Compression.Oodle, "Oodle"),
            Triple(Version.V11, Compression.Zlib, "Zlib")
        ).filter { oodleUsable || it.second != Compression.Oodle }
        for ((version, compression, compStr) in cases) {
            val pak = tempDir("zenpaksvc_v").resolve("p.pak")
            ZenPakService.repak_pack(
                inputDir = inDir, outputPak = pak,
                version = version, compression = compression
            )
            val info = ZenPakService.repak_info(pak)
            assertEquals(version.version_major().toString(), info["version_major"], "version_major for $version/${compression}")
            assertEquals("2", info["file_entries"], "file_entries for $version/$compression")
            assertEquals(compStr, info["compression"], "compression for $version/$compression")

            val outDir = tempDir("zenpaksvc_vout")
            ZenPakService.repak_unpack(pakFile = pak, outputDir = outDir)
            assertEquals("alpha", Files.readString(outDir.resolve("a.txt")))
            assertArrayEquals(Files.readAllBytes(sub.resolve("b.bin")), Files.readAllBytes(outDir.resolve("sub/b.bin")))
        }
    }

    @Test
    fun test_repak_pack_game_id_threadlocal() {
        // Two consecutive calls on the same thread with different game ids: the second (null)
        // must clear the first, and both packs must be valid. Exercises the ThreadLocal
        // game-id mechanism (EXC-005).
        val inDir = retocTests.resolve("UE5.5")
        val pakVoM = tempDir("zenpaksvc_gid").resolve("vom.pak")
        val pakDefault = tempDir("zenpaksvc_gid").resolve("default.pak")

        ZenPakService.repak_pack(inputDir = inDir, outputPak = pakVoM, game_id = "VisionsofMana")
        ZenPakService.repak_pack(inputDir = inDir, outputPak = pakDefault, game_id = null)

        // A pack written with the VisionsofMana game-id uses U8 compression index sizes and
        // must be read back with the same game id; the default pack reads without one.
        val outVoM = tempDir("zenpaksvc_gidout")
        ZenPakService.repak_unpack(pakFile = pakVoM, outputDir = outVoM, game_id = "VisionsofMana")
        assertTreesIdentical(inDir, outVoM)

        val outDefault = tempDir("zenpaksvc_gidout")
        ZenPakService.repak_unpack(pakFile = pakDefault, outputDir = outDefault)
        assertTreesIdentical(inDir, outDefault)
    }

    // ------------------------------------------------------------------
    // repak: upstream UnrealPak-generated reference packs
    // ------------------------------------------------------------------

    private val referenceVersions = listOf("v5", "v7", "v8a", "v8b", "v9", "v11")

    private fun versionMajorFor(name: String): String = when (name) {
        "v5" -> Version.V5.version_major().toString()
        "v7" -> Version.V7.version_major().toString()
        "v8a" -> Version.V8A.version_major().toString()
        "v8b" -> Version.V8B.version_major().toString()
        "v9" -> Version.V9.version_major().toString()
        "v11" -> Version.V11.version_major().toString()
        else -> throw IllegalArgumentException(name)
    }

    @Test
    fun test_repak_unpack_reference_packs() {
        for (v in referenceVersions) {
            for (suffix in listOf("", "_compress")) {
                val pak = repakPacks.resolve("pack_${v}${suffix}.pak")
                assertTrue(Files.exists(pak), "missing reference pack $pak")

                // Older UnrealPak versions record the mount differently
                // ("../mount/point/" vs "../mount/point/root/") — derive it from the pak.
                val mount = ZenPakService.repak_info(pak)["mount_point"]!!
                val outDir = tempDir("zenpaksvc_ref")
                ZenPakService.repak_unpack(pakFile = pak, outputDir = outDir, strip_prefix = mount)

                // Compare against the pak's own listing resolved into the reference tree
                // (the generated packs predate some files currently in pack/root).
                val listed = ZenPakService.repak_list(pak, strip_prefix = mount)
                assertTrue(listed.isNotEmpty(), "empty listing for $v$suffix")
                for (rel in listed) {
                    // listing is relative to the mount; entries may or may not carry the
                    // leading "root/" component depending on the generating UnrealPak
                    val src = repakPackRoot.resolve(rel.removePrefix("root/"))
                    assertTrue(Files.exists(src), "listed file $rel not in reference tree")
                    assertArrayEquals(Files.readAllBytes(src), Files.readAllBytes(outDir.resolve(rel)), "content mismatch for $rel ($v$suffix)")
                }

                val info = ZenPakService.repak_info(pak)
                assertEquals(versionMajorFor(v), info["version_major"], "version_major for $v$suffix")
                assertEquals(mount, info["mount_point"], "mount_point for $v$suffix")
                assertEquals("false", info["encrypted_index"], "encrypted_index for $v$suffix")
                if (suffix == "") assertEquals("None", info["compression"], "compression for $v plain")
            }
        }
    }

    @Test
    fun test_repak_unpack_encrypted_paks() {
        val variants = listOf(
            "pack_v11_encrypt.pak",
            "pack_v11_encryptindex.pak",
            "pack_v11_compress_encrypt.pak",
            "pack_v11_compress_encrypt_encryptindex.pak"
        )
        for (name in variants) {
            val pak = repakPacks.resolve(name)
            assertTrue(Files.exists(pak), "missing $name")

            // without key: must fail
            val failDir = tempDir("zenpaksvc_enc_fail")
            assertThrows<Exception>("$name must fail without key") {
                ZenPakService.repak_unpack(pakFile = pak, outputDir = failDir, strip_prefix = "../mount/point/")
            }

            // with key: full roundtrip against the pak's own listing
            val outDir = tempDir("zenpaksvc_enc_ok")
            ZenPakService.repak_unpack(pakFile = pak, outputDir = outDir, strip_prefix = "../mount/point/", aes_key = aesKey)
            val listed = ZenPakService.repak_list(pak, strip_prefix = "../mount/point/", aes_key = aesKey)
            for (rel in listed) {
                val src = repakPackRoot.resolve(rel.removePrefix("root/"))
                assertTrue(Files.exists(src), "listed file $rel not in reference tree")
                assertArrayEquals(Files.readAllBytes(src), Files.readAllBytes(outDir.resolve(rel)), "content mismatch for $rel ($name)")
            }
        }
    }

    @Test
    fun test_repak_info_encrypted_index_flag() {
        val info = ZenPakService.repak_info(repakPacks.resolve("pack_v11_encryptindex.pak"), aes_key = aesKey)
        assertEquals("true", info["encrypted_index"])
        assertEquals(Version.V11.version_major().toString(), info["version_major"])
    }

    @Test
    fun test_repak_include_filter() {
        val pak = repakPacks.resolve("pack_v11.pak")
        val outDir = tempDir("zenpaksvc_inc")
        ZenPakService.repak_unpack(
            pakFile = pak, outputDir = outDir,
            strip_prefix = "../mount/point/",
            include = listOf("root/*.txt", "root/directory/nested.txt")
        )
        assertTrue(Files.exists(outDir.resolve("root/test.txt")), "txt included")
        assertTrue(Files.exists(outDir.resolve("root/directory/nested.txt")), "nested txt included via exact pattern")
        assertFalse(Files.exists(outDir.resolve("root/test.png")), "png excluded")
        assertFalse(Files.exists(outDir.resolve("root/zeros.bin")), "bin excluded")
    }

    @Test
    fun test_repak_error_paths() {
        // pack: input not a directory
        val notADir = tempDir("zenpaksvc_err").resolve("file.txt")
        Files.write(notADir, "x".toByteArray())
        assertThrows<RepakError.InputNotADirectory> {
            ZenPakService.repak_pack(inputDir = notADir, outputPak = tempDir("e").resolve("o.pak"))
        }

        // unpack: invalid aes key format
        assertThrows<RepakError.Aes> {
            ZenPakService.repak_unpack(
                pakFile = repakPacks.resolve("pack_v11.pak"),
                outputDir = tempDir("e"), aes_key = "not-a-valid-key"
            )
        }

        // unpack: prefix mismatch
        assertThrows<RepakError.PrefixMismatch> {
            ZenPakService.repak_unpack(
                pakFile = repakPacks.resolve("pack_v11.pak"),
                outputDir = tempDir("e"), strip_prefix = "/nonexistent/prefix/"
            )
        }

        // list/info on missing file
        assertThrows<Exception> { ZenPakService.repak_list(tempDir("e").resolve("nope.pak")) }
    }

    // ------------------------------------------------------------------
    // retoc: to-zen across versions, filters, game store, passthrough
    // ------------------------------------------------------------------

    @Test
    fun test_retoc_to_zen_smoke() {
        val utoc = tempDir("zenpaksvc_tz").resolve("out.utoc")
        ZenPakService.retoc_to_zen(inputDir = retocTests.resolve("UE5.5"), outputUtoc = utoc, engine_version = EngineVersion.UE5_5)
        assertTrue(Files.exists(utoc) && Files.size(utoc) > 0, "utoc not written")
        val ucas = utoc.resolveSibling("out.ucas")
        assertTrue(Files.exists(ucas) && Files.size(ucas) > 0, "ucas not written")
        assertTrue(Files.exists(utoc.resolveSibling("out.pak")), "companion pak not written")
    }

    @Test
    fun test_retoc_to_zen_versions() {
        // UE5.4 fixture shader archives are file version 1, which write_io_store_library
        // rejects exactly like Rust ("Unknown shader library file version 1") — covered in
        // its own test below. Exclude them here to convert the assets.
        for ((dir, version) in listOf("UE5.4" to EngineVersion.UE5_4, "UE5.6" to EngineVersion.UE5_6)) {
            val inDir = tempDir("zenpaksvc_tzv_in")
            copyDir(retocTests.resolve(dir), inDir) { p -> p.toString().endsWith(".ushaderbytecode") }
            val utoc = tempDir("zenpaksvc_tzv").resolve("$dir.utoc")
            ZenPakService.retoc_to_zen(inputDir = inDir, outputUtoc = utoc, engine_version = version)
            assertTrue(Files.exists(utoc) && Files.size(utoc) > 0, "utoc not written for $dir")
            assertTrue(Files.exists(utoc.resolveSibling("$dir.ucas")), "ucas not written for $dir")
        }
    }

    @Test
    fun test_retoc_to_zen_v1_shader_library_rejected() {
        // Parity: Rust write_io_store_library bails on shader library file version != 2;
        // the UE5.4 fixture archives are version 1, so to-zen must fail with the same error.
        val utoc = tempDir("zenpaksvc_shdr").resolve("sh.utoc")
        val err = assertThrows<IllegalArgumentException> {
            ZenPakService.retoc_to_zen(inputDir = retocTests.resolve("UE5.4"), outputUtoc = utoc, engine_version = EngineVersion.UE5_4)
        }
        assertTrue(err.message!!.contains("Unknown shader library file version 1"), err.message)
    }

    private fun countExportBundleChunks(utoc: Path): Int {
        val iostore = open(utoc, Config())
        return iostore.chunks().count { it.id().get_chunk_type() == EIoChunkType.ExportBundleData }
    }

    @Test
    fun test_retoc_to_zen_filter() {
        val all = tempDir("zenpaksvc_f").resolve("all.utoc")
        ZenPakService.retoc_to_zen(inputDir = retocTests.resolve("UE5.5"), outputUtoc = all, engine_version = EngineVersion.UE5_5)
        val filtered = tempDir("zenpaksvc_f").resolve("filtered.utoc")
        ZenPakService.retoc_to_zen(inputDir = retocTests.resolve("UE5.5"), outputUtoc = filtered, engine_version = EngineVersion.UE5_5, filter = "SM_Cube")

        // 2 asset packages + the EXC-011 /Engine/UnknownPackage sentinel package (unfiltered);
        // the filtered conversion yields just SM_Cube
        assertEquals(3, countExportBundleChunks(all), "unfiltered package count")
        assertEquals(1, countExportBundleChunks(filtered), "filtered package count")
    }

    @Test
    fun test_retoc_to_zen_game_store_harvest() {
        // Build container A, then convert UE5.4 with A as --game-store: exercises the
        // game package-name harvesting path (EXC-011).
        val storeA = tempDir("zenpaksvc_gs").resolve("storeA.utoc")
        ZenPakService.retoc_to_zen(inputDir = retocTests.resolve("UE5.5"), outputUtoc = storeA, engine_version = EngineVersion.UE5_5)

        val inB = tempDir("zenpaksvc_gs_in")
        copyDir(retocTests.resolve("UE5.4"), inB) { p -> p.toString().endsWith(".ushaderbytecode") }
        val outB = tempDir("zenpaksvc_gs").resolve("storeB.utoc")
        ZenPakService.retoc_to_zen(
            inputDir = inB, outputUtoc = outB,
            engine_version = EngineVersion.UE5_4, game_store = listOf(storeA)
        )
        assertTrue(Files.exists(outB) && Files.size(outB) > 0)
    }

    @Test
    fun test_retoc_unpack_passthrough_and_filter() {
        // Non-asset files become passthrough chunks with paths; retoc_unpack extracts them.
        val inDir = tempDir("zenpaksvc_pt_in")
        copyDir(retocTests.resolve("UE5.5"), inDir)
        Files.createDirectories(inDir.resolve("docs"))
        Files.writeString(inDir.resolve("docs/readme.txt"), "passthrough me")

        val utoc = tempDir("zenpaksvc_pt").resolve("pt.utoc")
        ZenPakService.retoc_to_zen(inputDir = inDir, outputUtoc = utoc, engine_version = EngineVersion.UE5_5)

        val outAll = tempDir("zenpaksvc_pt_all")
        ZenPakService.retoc_unpack(utocFile = utoc, outputDir = outAll)
        assertTrue(Files.exists(outAll.resolve("docs/readme.txt")), "passthrough file extracted")
        assertEquals("passthrough me", Files.readString(outAll.resolve("docs/readme.txt")))

        val outFiltered = tempDir("zenpaksvc_pt_f")
        ZenPakService.retoc_unpack(utocFile = utoc, outputDir = outFiltered, filter = "nomatch-xyz")
        assertFalse(Files.exists(outFiltered.resolve("docs/readme.txt")), "filter must exclude everything here")
    }

    @Test
    fun test_retoc_multi_input_unpack() {
        // Same engine version for both containers: EXC-009 keeps Rust's refusal to mix
        // container versions without --override-toc-version. Passthrough chunk ids are
        // sequential from 0 per to-zen run (Rust parity), so A is stripped of passthrough
        // files to keep the two containers' external chunks disjoint.
        val excludePassthrough: (Path) -> Boolean =
            { p -> val n = p.fileName.toString(); n == "AssetRegistry.bin" || n.endsWith(".uzenasset") }

        val inA = tempDir("zenpaksvc_mi_a")
        copyDir(retocTests.resolve("UE5.5"), inA, excludePassthrough)
        val utocA = tempDir("zenpaksvc_mi").resolve("a.utoc")
        ZenPakService.retoc_to_zen(inputDir = inA, outputUtoc = utocA, engine_version = EngineVersion.UE5_5)

        val inB = tempDir("zenpaksvc_mi_b")
        copyDir(retocTests.resolve("UE5.5"), inB, excludePassthrough)
        Files.writeString(inB.resolve("extra_b.txt"), "from B")
        val utocB = tempDir("zenpaksvc_mi").resolve("b.utoc")
        ZenPakService.retoc_to_zen(inputDir = inB, outputUtoc = utocB, engine_version = EngineVersion.UE5_5)

        val out = tempDir("zenpaksvc_mi_out")
        ZenPakService.retoc_unpack(inputFiles = listOf(utocA, utocB), outputDir = out)
        assertTrue(Files.exists(out.resolve("extra_b.txt")), "second container extracted")
    }

    @Test
    fun test_retoc_multi_input_first_match_priority() {
        // Passthrough chunk ids are sequential from 0 in every to-zen container (Rust parity,
        // retoc_cli/main.rs:1060-1077), so two containers' first passthrough files share the
        // same chunk id; the composite resolves by first-match priority — the first container
        // in the input list wins.
        val work = tempDir("zenpaksvc_fm")
        val inA = work.resolve("inA"); Files.createDirectories(inA)
        Files.writeString(inA.resolve("x.txt"), "from A")
        val inB = work.resolve("inB"); Files.createDirectories(inB)
        Files.writeString(inB.resolve("y.txt"), "from B")

        val utocA = work.resolve("a.utoc")
        val utocB = work.resolve("b.utoc")
        ZenPakService.retoc_to_zen(inputDir = inA, outputUtoc = utocA, engine_version = EngineVersion.UE5_5)
        ZenPakService.retoc_to_zen(inputDir = inB, outputUtoc = utocB, engine_version = EngineVersion.UE5_5)

        val out = tempDir("zenpaksvc_fm_out")
        ZenPakService.retoc_unpack(inputFiles = listOf(utocA, utocB), outputDir = out)
        assertTrue(Files.exists(out.resolve("x.txt")), "first container's chunk wins")
        assertEquals("from A", Files.readString(out.resolve("x.txt")))
        assertFalse(Files.exists(out.resolve("y.txt")), "second container's colliding chunk shadowed")
    }

    @Test
    fun test_retoc_mixed_toc_versions_override() {
        // Containers of different TOC versions refuse to composite without an override
        // (EXC-009 Rust parity); override_toc_version permits the mix, like the CLI's
        // --override-toc-version. B is written with a forced older TOC version so the pair
        // is mixed without needing game fixtures.
        val work = tempDir("zenpaksvc_mixed")
        val inA = work.resolve("inA"); Files.createDirectories(inA)
        Files.writeString(inA.resolve("x.txt"), "from A")
        val inB = work.resolve("inB"); Files.createDirectories(inB)
        Files.writeString(inB.resolve("y.txt"), "from B")

        val utocA = work.resolve("a.utoc")
        val utocB = work.resolve("b.utoc")
        ZenPakService.retoc_to_zen(inputDir = inA, outputUtoc = utocA, engine_version = EngineVersion.UE5_5)
        ZenPakService.retoc_to_zen(inputDir = inB, outputUtoc = utocB, engine_version = EngineVersion.UE5_5,
            override_toc_version = EIoStoreTocVersion.PartitionSize)

        assertThrows<IllegalArgumentException> {
            ZenPakService.retoc_unpack(
                inputFiles = listOf(utocA, utocB), outputDir = tempDir("zenpaksvc_mixed_fail"))
        }

        val out = tempDir("zenpaksvc_mixed_out")
        ZenPakService.retoc_unpack(inputFiles = listOf(utocA, utocB), outputDir = out,
            override_toc_version = EIoStoreTocVersion.PartitionSize)
        assertEquals("from A", Files.readString(out.resolve("x.txt")))
        assertEquals("from B", Files.readString(out.resolve("y.txt")))
    }

    // ------------------------------------------------------------------
    // retoc: to-legacy
    // ------------------------------------------------------------------

    @Test
    fun test_retoc_to_legacy_script_objects_container() {
        // gen-script-objects produces a container WITH a ScriptObjects chunk, which is what
        // FZenPackageContext.create requires (Rust-parity); to-legacy then succeeds and writes
        // scriptobjects.bin into the output directory.
        val work = tempDir("zenpaksvc_leg")
        val jmap = work.resolve("jmap.json")
        Files.writeString(
            jmap, """
            {"objects": {
              "/Script/CoreUObject": {"type": "Package"},
              "/Script/Engine": {"type": "Package"},
              "/Script/Engine.BlueprintFunctionLibrary": {"type": "Class", "outer": "/Script/Engine",
                "class_default_object": "/Script/Engine.Default__BlueprintFunctionLibrary"}
            }}
            """.trimIndent()
        )
        val utoc = work.resolve("so.utoc")
        action_gen_script_objects(
            ActionGenScriptObjects(input = jmap, output = utoc, version = EngineVersion.UE5_5),
            Config()
        )
        assertTrue(Files.exists(utoc) && Files.size(utoc) > 0, "script objects container not written")

        val outDir = work.resolve("legacy_out")
        Files.createDirectories(outDir)
        ZenPakService.retoc_to_legacy(inputFile = utoc, outputDir = outDir, engine_version = EngineVersion.UE5_5)
        assertTrue(Files.exists(outDir.resolve("scriptobjects.bin")), "scriptobjects.bin not written by to-legacy")
    }

    @Test
    fun test_retoc_to_legacy_missing_script_objects_throws() {
        // Documented Rust-parity limitation: synthetic to-zen containers carry no ScriptObjects
        // chunk and FZenPackageContext.create requires one for UE5.5-era packages.
        val utoc = tempDir("zenpaksvc_leg2").resolve("no_so.utoc")
        ZenPakService.retoc_to_zen(inputDir = retocTests.resolve("UE5.5"), outputUtoc = utoc, engine_version = EngineVersion.UE5_5)
        val outDir = tempDir("zenpaksvc_leg2_out")
        assertThrows<Exception> {
            ZenPakService.retoc_to_legacy(inputFile = utoc, outputDir = outDir, engine_version = EngineVersion.UE5_5)
        }
    }

    // ------------------------------------------------------------------
    // availability probe
    // ------------------------------------------------------------------

    @Test
    fun test_is_available_does_not_throw() {
        ZenPakService.is_available()
    }

    // ------------------------------------------------------------------
    // in-memory seams (automod GenerateMod)
    // ------------------------------------------------------------------

    /** Script-objects container (the only synthetic container to-legacy accepts, Rust-parity). */
    private fun scriptObjectsContainer(work: Path): Path {
        val jmap = work.resolve("jmap.json")
        Files.writeString(
            jmap, """
            {"objects": {
              "/Script/CoreUObject": {"type": "Package"},
              "/Script/Engine": {"type": "Package"},
              "/Script/Engine.BlueprintFunctionLibrary": {"type": "Class", "outer": "/Script/Engine",
                "class_default_object": "/Script/Engine.Default__BlueprintFunctionLibrary"}
            }}
            """.trimIndent()
        )
        val utoc = work.resolve("so.utoc")
        action_gen_script_objects(
            ActionGenScriptObjects(input = jmap, output = utoc, version = EngineVersion.UE5_5),
            Config()
        )
        return utoc
    }

    @Test
    fun test_retoc_to_legacy_extract_memory_matches_disk() {
        val work = tempDir("zenpaksvc_mem")
        val utoc = scriptObjectsContainer(work)

        val collected = HashMap<String, ByteArray>()
        ZenPakService.retoc_to_legacy_extract(
            inputFiles = listOf(utoc), engine_version = EngineVersion.UE5_5
        ) { path, _, data -> collected[path] = data }

        val diskDir = work.resolve("disk_out"); Files.createDirectories(diskDir)
        ZenPakService.retoc_to_legacy(inputFile = utoc, outputDir = diskDir, engine_version = EngineVersion.UE5_5)

        val diskFiles = Files.walk(diskDir).use { s ->
            s.filter { Files.isRegularFile(it) }.map { diskDir.relativize(it).toString().replace('\\', '/') }.toList()
        }
        assertTrue("scriptobjects.bin" in collected, "callback must deliver scriptobjects.bin")
        assertEquals(diskFiles.toSet(), collected.keys, "memory and disk outputs must cover the same files")
        for (rel in diskFiles) {
            assertArrayEquals(Files.readAllBytes(diskDir.resolve(rel)), collected[rel], "content mismatch for $rel")
        }
    }

    @Test
    fun test_retoc_to_zen_from_provider_byte_identical() {
        // Passthrough files are excluded: their chunk ids are assigned in list_files order
        // (Rust parity), which is intentionally free for a custom provider; converted assets
        // are written in sorted package_id order (EXC-004), so asset-only containers are
        // byte-identical regardless of provider listing order.
        val inDir = tempDir("zenpaksvc_prov_in")
        copyDir(retocTests.resolve("UE5.5"), inDir) { p ->
            val n = p.fileName.toString()
            n == "AssetRegistry.bin" || n.endsWith(".uzenasset") || n.endsWith(".ushaderbytecode")
        }

        // NOTE: the TOC embeds a seed derived from the output FILE NAME (verified: same name ->
        // byte-identical across runs/implementations; different name -> fixed diff bytes), so
        // both runs must write the same basename.
        val ref = tempDir("zenpaksvc_prov").resolve("out.utoc")
        ZenPakService.retoc_to_zen(inputDir = inDir, outputUtoc = ref, engine_version = EngineVersion.UE5_5)

        // Provider lists in REVERSE sorted order on purpose: output must not depend on it.
        val allFiles = Files.walk(inDir).use { s ->
            s.filter { Files.isRegularFile(it) }.map { inDir.relativize(it).toString().replace('\\', '/') }.toList()
        }
        val provided = allFiles.sortedDescending()
        val out = tempDir("zenpaksvc_prov2").resolve("out.utoc")
        ZenPakService.retoc_to_zen_from(
            list_files = { provided },
            read = { p -> Files.readAllBytes(inDir.resolve(p)) },
            read_opt = { p -> if (Files.exists(inDir.resolve(p))) Files.readAllBytes(inDir.resolve(p)) else null },
            outputUtoc = out, engine_version = EngineVersion.UE5_5
        )

        assertEquals(-1L, Files.mismatch(ref, out), "provider-built container must be byte-identical")
    }
}
