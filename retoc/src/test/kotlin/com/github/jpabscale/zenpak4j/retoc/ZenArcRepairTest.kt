// Copyright (c) 2026 jpabscale — legacy-arc repair tests (game-gated fixtures).
package com.github.jpabscale.zenpak4j.retoc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ZenArcRepairTest {
    private val cookerChunk: Path = Path.of(
        System.getenv("SB_PKG_CHUNK") ?: "/tmp/opencode/hb-work/dump-test-game/CH_P_EVE_01_Blueprint.uasset")
    private val convertedChunk: Path = Path.of(
        System.getenv("SB_CONVERTED_CHUNK") ?: "/tmp/opencode/hb-work/dump-test-mod/CH_P_EVE_01_Blueprint.uasset")

    @Test
    fun cookerChunkIsAlreadyCleanAndSelfRepairChangesNothing() {
        assumeTrue(Files.isRegularFile(cookerChunk), "cooker fixture missing at $cookerChunk")
        val cooker = Files.readAllBytes(cookerChunk)
        assertTrue(zen_package_warnings(cooker).isEmpty(), "cooker arcs are already valid")
        val (repaired, paired, heuristic) = repair_legacy_dependency_arcs(cooker, cooker)
        assertEquals(0, paired + heuristic, "nothing to repair against itself")
        assertArrayEquals(cooker, repaired)
    }

    @Test
    fun convertedChunkArcsAreRepairedFromTheCooker() {
        assumeTrue(Files.isRegularFile(cookerChunk) && Files.isRegularFile(convertedChunk),
            "fixtures missing")
        val cooker = Files.readAllBytes(cookerChunk)
        val converted = Files.readAllBytes(convertedChunk)
        assertFalse(zen_package_warnings(converted).isEmpty(), "the conversion carries -1 arcs")
        val before = zen_package_warnings(converted).count { it.contains("legacy") }
        val (repaired, paired, heuristic) = repair_legacy_dependency_arcs(converted, cooker)
        val after = zen_package_warnings(repaired).count { it.contains("legacy") }
        println("arc repair: ${paired + heuristic} field(s) written ($paired paired, $heuristic heuristic); " +
            "affected packages $before -> $after")
        assertTrue(paired + heuristic > 0, "the conversion's negative arcs must be repaired")
        assertTrue(paired > heuristic, "most repairs come from the cooker's own arcs")
        assertTrue(after == 0, "every negative arc is repaired ($before -> $after packages)")
        assertArrayEquals(converted, converted, "the source is never mutated")
    }
}
