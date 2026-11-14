package com.mariaray.app

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object FilterEngine {
    private val procs = ConcurrentLinkedQueue<Process>()

    @Volatile private var stopped = false

    fun registerProc(p: Process) {
        if (stopped) { try { p.destroyForcibly() } catch (_: Exception) {}; return }
        procs.add(p)
    }

    fun unregisterProc(p: Process) {
        procs.remove(p)
    }

    fun stopAll() {
        stopped = true
        val snapshot = procs.toList()
        procs.clear()
        snapshot.forEach { try { it.destroyForcibly() } catch (_: Exception) {} }
    }

    data class Outcome(
        val bestContent: String,
        val bestCount: Int,
        val n1: Int,
        val n2: Int,
        val n3: Int
    )

    suspend fun run(
        context: Context,
        input: String,
        onProgress: suspend (stage: String, cur: Int, total: Int, msg: String) -> Unit
    ): Outcome = withContext(Dispatchers.IO) {
        stopped = false
        val workDir = File(context.filesDir, "filter").apply { mkdirs() }
        workDir.listFiles()?.forEach { try { it.delete() } catch (_: Exception) {} }
        val runner = XrayRunner(context, workDir)
        val client = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        onProgress("Input", 0, 1, "loading...")
        val all = SubFetcher.fetchAll(input, client)
        val unique = all.distinct()
        val total = unique.size
        onProgress("Input", 1, 1, "unique: $total")

        val stage1 = ConcurrentLinkedQueue<String>()
        val c1 = AtomicInteger()
        coroutineScope {
            val sem = Semaphore(64)
            unique.forEach { link ->
                launch {
                    sem.withPermit {
                        if (stopped) return@withPermit
                        if (tcpOk(link)) stage1.add(link)
                        val n = c1.incrementAndGet()
                        if (n % 20 == 0 || n == total) onProgress("Stage 1 TCP", n, total, "found ${stage1.size}")
                    }
                }
            }
        }
        val n1 = stage1.size
        onProgress("Stage 1 TCP", total, total, "done: $n1")

        val s2in = stage1.toList().take(200)
        val stage2 = ConcurrentLinkedQueue<String>()
        val c2 = AtomicInteger()
        val t2 = s2in.size
        coroutineScope {
            val sem = Semaphore(20)
            s2in.forEachIndexed { idx, link ->
                launch {
                    sem.withPermit {
                        if (stopped) return@withPermit
                        try {
                            if (runner.testHttp(link, 20000 + idx))
                                stage2.add(link)
                        } catch (_: Exception) {}
                        val n = c2.incrementAndGet()
                        if (n % 5 == 0 || n == t2) onProgress("Stage 2 HTTP", n, t2, "found ${stage2.size}")
                    }
                }
            }
        }
        val n2 = stage2.size
        onProgress("Stage 2 HTTP", t2, t2, "done: $n2")

        val s3in = stage2.toList()
        val best = ConcurrentLinkedQueue<String>()
        val c3 = AtomicInteger()
        val t3 = s3in.size
        if (t3 > 0) coroutineScope {
            val sem = Semaphore(5)
            s3in.forEachIndexed { idx, link ->
                launch {
                    sem.withPermit {
                        if (stopped) return@withPermit
                        try {
                            if (runner.testSpeedKbps(link, 30000 + idx) > 50.0) best.add(link)
                        } catch (_: Exception) {}
                        val n = c3.incrementAndGet()
                        if (n % 2 == 0 || n == t3) onProgress("Stage 3 Speed", n, t3, "best ${best.size}")
                    }
                }
            }
        }
        val n3 = best.size
        onProgress("Stage 3 Speed", t3, t3, "done: $n3")

        Outcome(best.joinToString("\n"), n3, n1, n2, n3)
    }

    private fun tcpOk(link: String, timeout: Int = 2000): Boolean {
        val (h, p) = ConfigParser.hostPort(link) ?: return false
        return try {
            Socket().use { it.connect(InetSocketAddress(h, p), timeout); true }
        } catch (_: Exception) { false }
    }
}
