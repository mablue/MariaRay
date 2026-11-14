package com.mariaray.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.mariaray.app.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var lastBest: String = ""
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etInput.setText(
            "https://raw.githubusercontent.com/v2FreeHub/v2hub-configs/refs/heads/main/Sub-AutoUpdate"
        )

        binding.btnFilter.setOnClickListener { startFilter() }
        binding.btnCopy.setOnClickListener {
            if (lastBest.isEmpty()) { toast("Nothing to copy"); return@setOnClickListener }
            val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cb.setPrimaryClip(ClipData.newPlainText("subs", lastBest))
            toast("Copied ${lastBest.lines().count { it.isNotBlank() }} configs")
        }
        binding.btnStop.setOnClickListener {
            job?.cancel()
            FilterEngine.stopAll()
            binding.tvStatus.text = "Stopping..."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FilterEngine.stopAll()
    }

    private fun startFilter() {
        // cancel previous run if any
        job?.cancel()
        job = null

        val input = binding.etInput.text?.toString()?.trim().orEmpty()
        if (input.isEmpty()) { toast("Enter URL or paste configs"); return }

        binding.progressBar.progress = 0
        binding.tvStatus.text = "Starting..."
        binding.tvOutput.text = ""
        lastBest = ""
        binding.btnFilter.isEnabled = false
        binding.btnStop.isEnabled = true

        job = lifecycleScope.launch {
            try {
                val result = FilterEngine.run(this@MainActivity, input) { stage, cur, total, msg ->
                    withContext(Dispatchers.Main) {
                        binding.tvStatus.text = "$stage: $cur/$total — $msg"
                        if (total > 0) binding.progressBar.progress = (cur * 100 / total)
                    }
                }
                lastBest = result.bestContent
                binding.tvOutput.text = buildString {
                    appendLine("✔ ${result.bestCount} configs kept")
                    appendLine("Stage1 (TCP): ${result.n1}")
                    appendLine("Stage2 (HTTP): ${result.n2}")
                    appendLine("Stage3 (speed): ${result.n3}")
                    appendLine()
                    append(result.bestContent)
                }
                binding.tvStatus.text = "Done. ${result.bestCount} configs"
            } catch (e: CancellationException) {
                binding.tvStatus.text = "Cancelled"
                throw e
            } catch (e: Exception) {
                binding.tvStatus.text = "Error: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                binding.btnFilter.isEnabled = true
                binding.btnStop.isEnabled = false
            }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
