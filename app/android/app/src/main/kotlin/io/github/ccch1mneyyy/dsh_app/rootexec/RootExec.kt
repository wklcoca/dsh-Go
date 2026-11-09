package io.github.ccch1mneyyy.dsh_app.rootexec

import io.github.ccch1mneyyy.dsh_app.phone.McpServer
import io.github.ccch1mneyyy.dsh_app.phone.PhoneBridge
import io.github.ccch1mneyyy.dsh_app.phone.PhoneGuest
import io.github.ccch1mneyyy.dsh_app.phone.ToolOutput
import io.github.ccch1mneyyy.dsh_app.runtime.RootMode
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Minimal app-side root exec: runs `su -c <cmd>` from the APP process (never inside proot), so the
 * child lives in the real Android mount namespace with TracerPid 0. Lets the guest start
 * frida-server, mount, nsenter &c. — the operations proot's ptrace sandbox forbids.
 *
 * Exposed to the guest as a tiny MCP tool (root_exec) over a private abstract socket; the guest
 * CLI `dsh-root` (a Node script) reads `/var/lib/dsh-app/root/bridge.json` and calls it.
 */
object RootExec {
    private const val MAX_CAP = 512 * 1024

    val tool: Map<String, Any?> = linkedMapOf(
        "name" to "root_exec",
        "description" to "在 proot 之外、以真 root 执行一条命令（由 App 进程 su），返回 exitCode/stdout/stderr。",
        "inputSchema" to linkedMapOf<String, Any?>(
            "type" to "object",
            "properties" to linkedMapOf<String, Any?>(
                "cmd" to linkedMapOf<String, Any?>("type" to "string", "description" to "要执行的 shell 命令"),
                "timeout_ms" to linkedMapOf<String, Any?>("type" to "integer", "description" to "超时(毫秒)，默认 120000"),
                "background" to linkedMapOf<String, Any?>("type" to "boolean", "description" to "后台启动(setsid)，立即返回"),
            ),
            "required" to listOf("cmd"),
            "additionalProperties" to false,
        ),
    )

    fun call(name: String, args: Map<String, Any?>): ToolOutput {
        val cmd = (args["cmd"] as? String)?.trim().orEmpty()
        if (cmd.isEmpty()) return ToolOutput("缺少 cmd 参数", isError = true)
        val timeout = ((args["timeout_ms"] as? Number)?.toLong() ?: 120_000L).coerceIn(1_000L, 600_000L)
        val bg = args["background"] == true
        return try {
            ToolOutput(execute(cmd, timeout, bg))
        } catch (e: Throwable) {
            ToolOutput("root_exec 失败：${e.javaClass.simpleName}${e.message?.let { "：$it" } ?: ""}", isError = true)
        }
    }

    fun execute(cmd: String, timeoutMs: Long, background: Boolean): String {
        val su = RootMode.suPath() ?: "su"
        // Foreground: exact command. Background: detach into a new session so a daemon survives.
        val launcher = if (background) "setsid sh -c ${shq(cmd)} >/dev/null 2>&1 </dev/null &" else cmd
        val p = ProcessBuilder(su, "-c", launcher)
            .redirectErrorStream(false)
            .start()
        if (background) {
            val quick = p.waitFor(5, TimeUnit.SECONDS)
            if (!quick) runCatching { p.destroy() }
            return "started(background): $cmd"
        }
        val out = StringBuilder()
        val err = StringBuilder()
        val to = Thread { p.inputStream.bufferedReader().use { r -> copyLimited(r, out) } }
        val te = Thread { p.errorStream.bufferedReader().use { r -> copyLimited(r, err) } }
        to.isDaemon = true
        te.isDaemon = true
        to.start()
        te.start()
        val done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!done) {
            runCatching { p.destroyForcibly() }
            to.join(800)
            te.join(800)
            return "timeout=${timeoutMs}ms\n--- stdout ---\n$out\n--- stderr ---\n$err"
        }
        to.join(1500)
        te.join(1500)
        return "exitCode=${p.exitValue()}\n--- stdout ---\n$out\n--- stderr ---\n$err"
    }

    private fun copyLimited(r: java.io.BufferedReader, sb: StringBuilder) {
        val buf = CharArray(8192)
        var total = 0
        while (true) {
            val n = r.read(buf)
            if (n < 0) break
            total += n
            if (total <= MAX_CAP) sb.append(buf, 0, n)
        }
    }

    private fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

/**
 * Lifecycle holder: at every engine start the app (re)binds a private socket and writes its
 * endpoint + token into the rootfs so the guest `dsh-root` client can reach it. Off in safe mode.
 */
object RootExecBridge {
    private var bridge: PhoneBridge? = null

    @Synchronized
    fun prepareLaunch(rootfs: File, safe: Boolean, log: (String) -> Unit): Boolean {
        runCatching { bridge?.stop() }
        bridge = null
        if (safe) return false
        return try {
            val server = McpServer(listOf(RootExec.tool), { name, args, _ -> RootExec.call(name, args) })
            val b = PhoneBridge(server) { line -> log(line) }
            val ep = b.start(withTcp = true)
            bridge = b
            writeConfig(rootfs, ep)
            log("[dsh-app] root exec bridge listening on @" + (ep.abstractName ?: "-") + (ep.tcpPort?.let { " and 127.0.0.1:$it" } ?: ""))
            true
        } catch (e: Throwable) {
            log("[dsh-app] root exec bridge failed: ${e.message}")
            false
        }
    }

    @Synchronized
    fun stop() {
        runCatching { bridge?.stop() }
        bridge = null
    }

    private fun writeConfig(rootfs: File, ep: PhoneGuest.Endpoint) {
        val dir = File(rootfs, "var/lib/dsh-app/root")
        if (!dir.isDirectory && !dir.mkdirs()) return
        val f = File(dir, "bridge.json")
        f.writeText(PhoneGuest.config(ep))
        runCatching {
            f.setReadable(true, true)
            f.setWritable(true, true)
        }
    }
}
