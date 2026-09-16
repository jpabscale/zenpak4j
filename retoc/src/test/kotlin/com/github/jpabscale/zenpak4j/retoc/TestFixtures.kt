// Copyright (c) 2026 jpabscale — original tests (not part of the repak/retoc port)
// Upstream retoc test fixtures are NOT committed. Every candidate root is either a
// trumank/retoc repo checkout or an extracted tarball of one; fixtures live at
// <root>/retoc/tests. Roots tried:
//   1. $ZENPAK4J_FIXTURES
//   2. $GITHUB_WORKSPACE/build/fixtures  (act/copy-in: the fetch step populated it there)
//   3. <any ancestor of cwd>/build/fixtures  (`./gradlew downloadFixtures`)
//   4. sibling upstream checkouts (<ancestor>/../retoc == trumank/retoc clone)
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object TestFixtures {
    fun find(relative: String): Path? = candidateRoots()
        .flatMap { listOf(it.resolve("retoc/tests"), it.resolve("retoc/retoc/tests")).map { x -> x.resolve(relative) } }
        .firstOrNull { Files.exists(it) }

    fun require(relative: String): Path =
        find(relative) ?: throw IllegalStateException(
            "retoc fixture not found: $relative — run './gradlew downloadFixtures', " +
                "set ZENPAK4J_FIXTURES, or place a trumank/retoc checkout next to this repo")

    private fun candidateRoots(): List<Path> {
        val out = mutableListOf<Path>()
        System.getenv("ZENPAK4J_FIXTURES")?.takeIf { it.isNotBlank() }?.let { out.add(Paths.get(it)) }
        System.getenv("GITHUB_WORKSPACE")?.takeIf { it.isNotBlank() }?.let {
            out.add(Path.of(it).resolve("build").resolve("fixtures"))
        }
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        repeat(6) {
            val d = dir ?: return@repeat
            out.add(d.resolve("build").resolve("fixtures"))
            out.add(d.resolve("build").resolve("fixtures").resolve("retoc"))
            out.add(d)
            d.parent?.let { parent ->
                out.add(parent.resolve("repak"))
                out.add(parent.resolve("retoc"))
            }
            dir = d.parent
        }
        return out.distinct()
    }
}
