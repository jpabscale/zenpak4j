// Copyright (c) 2026 jpabscale — guard tests: legacy (-1) arcs distinguish a conversion (game-gated).
package com.github.jpabscale.zenpak4j.retoc

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ZenValidateTest {
    private val cookerChunk: Path = Path.of(
        System.getenv("SB_PKG_CHUNK") ?: "/tmp/opencode/hb-work/dump-test-game/CH_P_EVE_01_Blueprint.uasset")
    private val convertedChunk: Path = Path.of(
        System.getenv("SB_CONVERTED_CHUNK") ?: "/tmp/opencode/hb-work/dump-test-mod/CH_P_EVE_01_Blueprint.uasset")

    @Test
    fun cookerChunkHasNoWarnings() {
        assumeTrue(Files.isRegularFile(cookerChunk), "cooker fixture missing at $cookerChunk")
        val warnings = zen_package_warnings(Files.readAllBytes(cookerChunk))
        assertTrue(warnings.isEmpty(), "cooker chunk must be warning-free, got: $warnings")
    }

    @Test
    fun cookerChunkValidatesCleanAndABrokenChunkDoesNot() {
        assumeTrue(Files.isRegularFile(cookerChunk), "cooker fixture missing at $cookerChunk")
        val chunk = Files.readAllBytes(cookerChunk)
        assertTrue(zen_package_validate(chunk).isEmpty(),
            "cooker chunk must validate clean, got: ${zen_package_validate(chunk)}")
        val truncated = chunk.copyOfRange(0, chunk.size / 2)
        assertTrue(zen_package_validate(truncated).isNotEmpty(),
            "a truncated chunk must report findings")
    }

    @Test
    fun convertedChunkReportsLegacyArcs() {
        assumeTrue(Files.isRegularFile(convertedChunk), "converted fixture missing at $convertedChunk")
        val warnings = zen_package_warnings(Files.readAllBytes(convertedChunk))
        println("guard: ${warnings.size} warning(s) on the converted chunk: ${warnings.take(3)}")
        assertTrue(warnings.isNotEmpty(), "a failed conversion must be reported")
        val findings = zen_package_validate(Files.readAllBytes(convertedChunk))
        assertTrue(findings.any { it.contains("arc") } || findings.any { it.contains("not self-consistent") },
            "validation reports the conversion's table damage: ${findings.take(3)}")
    }
}
