package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RootEngineReclaimTest {
    private val base = Files.createTempDirectory("root-reclaim").toFile()
    @After fun cleanup() { base.deleteRecursively() }

    private fun run(root: File): Pair<Int, List<String>> {
        val bin = File(base, "bin").apply { mkdirs() }
        val log = File(base, "calls")
        File(bin, "chown").apply {
            writeText("#!/bin/sh\nprintf '%s\\n' \"\$*\" >> " + RootMode.quote(log.path) + "\n")
            setExecutable(true)
        }
        val process = ProcessBuilder("sh", "-c", RootMode.engineReclaimCommand(root, 12345))
        process.environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
        val child = process.start()
        return child.waitFor() to if (log.exists()) log.readLines() else emptyList()
    }

    @Test fun reclaimsExactEngineTreesButNotWholeSystemDirectories() {
        val root = File(base, "root fs'").apply { mkdirs() }
        File(root, "opt/dsh").mkdirs()
        File(root, "usr/local/bin").mkdirs()
        Files.createSymbolicLink(File(root, "usr/local/bin/node").toPath(), java.nio.file.Path.of("/missing/node"))
        val (exit, calls) = run(root)
        assertEquals(0, exit)
        assertTrue(calls.contains("-hR 12345:12345 ${root.path}/opt/dsh"))
        assertTrue(calls.contains("-hR 12345:12345 ${root.path}/usr/local/bin/node"))
        assertFalse(calls.any { it == "-hR 12345:12345 ${root.path}/opt" || it == "-hR 12345:12345 ${root.path}/usr" })
    }

    @Test fun refusesSymlinkedParentsBeforeAnyChown() {
        val root = File(base, "rootfs").apply { mkdirs() }
        val outside = File(base, "outside").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "opt").toPath(), outside.toPath())
        val (exit, calls) = run(root)
        assertNotEquals(0, exit)
        assertTrue(calls.isEmpty())
    }
}
