package io.github.ccch1mneyyy.dsh_app.runtime

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * EXPERIMENTAL "real root" launch mode.
 *
 * When a working `su` exists (KernelSU / Magisk), [DshProcess] can launch the bundled
 * proot *through* su so proot itself runs as uid 0. Then proot's `-b host:guest` binds
 * can reach root-only paths (e.g. /data) that the app uid cannot read.
 *
 * Because proot is then root-owned, the app (an unprivileged uid) can no longer send it
 * signals; [kill] / [killProot] / [killStrays] run `su -c kill` instead. This mode is
 * opt-in by availability and must be verified on a rooted device. Default behaviour when
 * su is absent is unchanged.
 */
object RootMode {
    private val CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
    )

    fun suPath(): String? = CANDIDATES.firstOrNull { path ->
        val f = File(path)
        f.isFile && f.canExecute()
    }

    fun available(): Boolean = suPath() != null

    /**
     * Ask su to prove it grants root now (`id -u` == 0). On KernelSU/Magisk the first
     * call shows the grant prompt (user has up to 20s); a denial returns false so the
     * caller falls back to the normal unprivileged launch instead of failing.
     */
    fun granted(): Boolean = try {
        val su = suPath() ?: return false
        val p = ProcessBuilder(su, "-c", "id -u").redirectErrorStream(true).start()
        val ok = try { p.waitFor(20, TimeUnit.SECONDS) } catch (_: InterruptedException) { false }
        if (!ok) { p.destroyForcibly(); return false }
        val out = p.inputStream.bufferedReader().use { it.readText() }
        out.lineSequence().any { it.trim() == "0" }
    } catch (_: Exception) { false }

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** `KEY=VALUE ... exec argv...` for `su -c`, so su's env reset does not lose ours. */
    fun commandWithEnv(argv: List<String>, env: Map<String, String>): String {
        // Keys are identifier-safe; quote only the VALUES. Never quote the name:
        // sh does not treat a quoted "NAME"=v word as an assignment (it tries to
        // execute a program named `NAME=v`).
        val assigns = env.entries.joinToString(" ") { (k, v) -> "$k=${quote(v)}" }
        val cmd = argv.joinToString(" ") { quote(it) }
        return if (assigns.isEmpty()) "exec $cmd" else "export $assigns; exec $cmd"
    }

    private fun readCmdline(pid: Int): String? = try {
        val bytes = File("/proc/$pid/cmdline").readBytes()
        if (bytes.isEmpty()) null
        else String(bytes, Charsets.UTF_8).replace('\u0000', ' ').trim().ifEmpty { null }
    } catch (_: Exception) { null }

    private fun ppidOf(pid: Int): Int? = try {
        val stat = File("/proc/$pid/stat").readText()
        val tail = stat.substringAfterLast(") ", "").trim().split(Regex(" +"))
        tail.getOrNull(1)?.toIntOrNull()
    } catch (_: Exception) { null }

    private fun isProotFor(cmd: String, rootfsPath: String): Boolean =
        (cmd.contains("libproot.so") || cmd.contains("/proot")) && cmd.contains(rootfsPath)

    /** Walk up from a known guest pid to the proot that owns it (avoids /proc enumeration). */
    fun prootAncestorOf(guestPid: Int, rootfsPath: String): Int? {
        var pid = guestPid
        repeat(12) {
            val parent = ppidOf(pid) ?: return null
            if (parent <= 1) return null
            val cmd = readCmdline(parent) ?: return null
            if (isProotFor(cmd, rootfsPath)) return parent
            pid = parent
        }
        return null
    }

    /** Enumerate /proc for proot processes bound to this rootfs (works even if owner is root). */
    fun findProotPids(rootfsPath: String): List<Int> {
        val out = ArrayList<Int>()
        val names = File("/proc").list() ?: return out
        for (name in names) {
            val pid = name.toIntOrNull() ?: continue
            val cmd = readCmdline(pid) ?: continue
            if (isProotFor(cmd, rootfsPath)) out.add(pid)
        }
        return out
    }

    private fun canonical(rootfs: File): String = try { rootfs.canonicalPath } catch (_: Exception) { rootfs.absolutePath }

    /** Send a signal to a pid via `su -c kill` (app uid cannot signal a root proot directly). */
    fun kill(pid: Int, signal: Int): Boolean = try {
        val su = suPath() ?: return false
        ProcessBuilder(su, "-c", "kill -$signal $pid").start().waitFor() == 0
    } catch (_: Exception) { false }

    fun killProot(rootfs: File, signal: Int) {
        val path = canonical(rootfs)
        val pids = buildSet {
            addAll(findProotPids(path))
            prootAncestorOf(guestPidHint(rootfs) ?: return@buildSet, path)?.let { add(it) }
        }
        pids.forEach { kill(it, signal) }
    }

    /** Kill every root-owned proot for this rootfs; true when none remain. */
    fun killStrays(rootfs: File): Boolean {
        val path = canonical(rootfs)
        repeat(3) {
            val pids = findProotPids(path)
            if (pids.isEmpty()) return true
            pids.forEach { kill(it, 3 /* SIGQUIT */) }
            Thread.sleep(120)
        }
        return findProotPids(path).isEmpty()
    }

    /** Remove a stale file the app uid cannot unlink (e.g. a root-owned pid file in sticky /tmp). */
    fun remove(file: File): Boolean = try {
        val su = suPath() ?: return false
        ProcessBuilder(su, "-c", "rm -f -- " + quote(file.absolutePath)).start().waitFor() == 0 &&
            !java.nio.file.Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) { false }

    private fun guestPidHint(rootfs: File): Int? = try {
        File(rootfs, "tmp/dsh-web.pid").readText().trim().toIntOrNull()?.takeIf { it > 0 }
    } catch (_: Exception) { null }
}
