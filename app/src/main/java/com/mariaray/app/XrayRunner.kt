package com.mariaray.app

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit

class XrayRunner(context: Context, private val workDir: File) {
    val binPath: String = prepareBinary(context)
    private val execErrorFile = File(workDir, "exec_error.txt")

    private fun prepareBinary(context: Context): String {
        val native = File(context.applicationInfo.nativeLibraryDir, "libxray.so")
        val filesCopy = File(context.filesDir, "xray")
        val cacheCopy = File(context.cacheDir, "xray")
        val candidates = mutableListOf<File>()

        if (native.exists()) candidates.add(native)

        try {
            if (!filesCopy.exists() && native.exists()) native.copyTo(filesCopy, overwrite = true)
            if (filesCopy.exists()) { filesCopy.setExecutable(true, false); candidates.add(filesCopy) }
        } catch (_: Exception) {}

        try {
            if (!cacheCopy.exists() && native.exists()) native.copyTo(cacheCopy, overwrite = true)
            if (cacheCopy.exists()) { cacheCopy.setExecutable(true, false); candidates.add(cacheCopy) }
        } catch (_: Exception) {}

        return candidates.firstOrNull()?.absolutePath ?: native.absolutePath
    }

    fun testHttp(link: String, port: Int): Boolean {
        val cfg = ConfigParser.toXrayConfig(link, port) ?: return false
        val f = File(workDir, "c2_$port.json").apply { writeText(cfg) }
        val p = start(f) ?: return false
        return try {
            if (!waitPort(port)) return false
            for ((url, expected) in Probes.HTTP) {
                val code = httpCode(port, url)
                if (code == expected) return true
                if (code in 200..299) return true
            }
            false
        } finally {
            try { p.destroyForcibly() } catch (_: Exception) {}
            FilterEngine.unregisterProc(p)
            f.delete()
        }
    }

    fun testSpeedKbps(link: String, port: Int): Double {
        val cfg = ConfigParser.toXrayConfig(link, port) ?: return 0.0
        val f = File(workDir, "c3_$port.json").apply { writeText(cfg) }
        val p = start(f) ?: return 0.0
        return try {
            if (!waitPort(port)) 0.0 else downloadKbps(port)
        } finally {
            try { p.destroyForcibly() } catch (_: Exception) {}
            FilterEngine.unregisterProc(p)
            f.delete()
        }
    }

    private fun start(cfg: File): Process? {
        val candidates = listOf(
            File(binPath),
            File(workDir, "xray"),
            File("/data/local/tmp/xray")
        ).filter { it.exists() }.map { it.absolutePath }.distinct()

        if (candidates.isEmpty()) {
            logExecError("NONE", "no xray binary found anywhere")
            return null
        }

        for (path in candidates) {
            try {
                val p = ProcessBuilder(path, "run", "-c", cfg.absolutePath)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                    .start()
                FilterEngine.registerProc(p)
                return p
            } catch (e: Exception) {
                logExecError(path, "${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return null
    }

    private fun logExecError(path: String, err: String) {
        try {
            val f = File(path)
            val info = "[${System.currentTimeMillis()}] path=$path exists=${f.exists()} exec=${f.canExecute()} size=${f.length()} err=$err\n"
            execErrorFile.appendText(info)
        } catch (_: Exception) {}
    }

    fun readExecErrors(): String = try {
        if (execErrorFile.exists()) execErrorFile.readText() else "(no errors logged)"
    } catch (_: Exception) { "(read failed)" }

    private fun waitPort(port: Int, t: Int = 1500): Boolean {
        val s = System.currentTimeMillis()
        while (System.currentTimeMillis() - s < t) {
            try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }; return true }
            catch (_: Exception) { Thread.sleep(40) }
        }
        return false
    }

    private fun httpCode(port: Int, url: String): Int {
        val c = clientFor(port, 4)
        return try { c.newCall(Request.Builder().url(url).build()).execute().use { it.code } }
        catch (_: Exception) { 0 }
    }

    private fun downloadKbps(port: Int): Double {
        val c = clientFor(port, 8)
        return try {
            val req = Request.Builder().url("https://speed.cloudflare.com/__down?bytes=200000").build()
            val t0 = System.currentTimeMillis()
            val n = c.newCall(req).execute().use { it.body?.bytes()?.size ?: 0 }
            val sec = (System.currentTimeMillis() - t0) / 1000.0
            if (sec > 0) (n / 1024.0) / sec else 0.0
        } catch (_: Exception) { 0.0 }
    }

    private fun clientFor(port: Int, timeoutSec: Long): OkHttpClient =
        OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .build()
}
