package com.technologyfrontier.qide.adapter

import com.technologyfrontier.qide.application.Cancellation
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** 子プロセスを起動する方法。IDE では GeneralCommandLine、試験では JDK の ProcessBuilder を使う。 */
fun interface ProcessLauncher {
    /** シェルを介さずに [executable] を [arguments] で起動する。 */
    fun launch(executable: Path, arguments: List<String>, workingDirectory: Path?): Process
}

class JdkProcessLauncher : ProcessLauncher {
    override fun launch(executable: Path, arguments: List<String>, workingDirectory: Path?): Process =
        ProcessBuilder(listOf(executable.toString()) + arguments)
            .apply { workingDirectory?.let { directory(it.toFile()) } }
            .redirectErrorStream(false)
            .start()
}

/**
 * 応答上限と監視期限（機能設計書 7.1）。
 * run の監視期限は、量子サービスの実行期限 120 秒＋qosctl の通信余裕 30 秒（qosctl の応答待ち 150 秒）に、
 * IDE 側の余裕 30 秒を加えた 180 秒。qosctl が先に TRANSPORT_ERROR（終了値 4）を返す前提とする。
 * 量子OS側の値が確定・変更されたら合わせて見直す。
 */
data class ExecutionLimits(
    val stdoutMaxBytes: Int = 16 * 1024 * 1024,
    val stderrMaxBytes: Int = 64 * 1024,
    val validateTimeout: Duration = Duration.ofSeconds(180),
    val statusTimeout: Duration = Duration.ofSeconds(180),
    val runTimeout: Duration = Duration.ofSeconds(180),
    /** 終了要求後、強制終了するまでの猶予。 */
    val terminateGrace: Duration = Duration.ofSeconds(3),
)

sealed interface ProcessOutcome {
    data class Completed(val exitCode: Int, val stdout: String, val stderr: String) : ProcessOutcome
    data class LaunchFailed(val message: String) : ProcessOutcome
    data class OutputLimitExceeded(val stream: String) : ProcessOutcome
    data class MalformedEncoding(val stream: String) : ProcessOutcome
    data class TimedOut(val timeout: Duration) : ProcessOutcome
    data object Cancelled : ProcessOutcome
}

/**
 * プロセスを起動し、標準出力と標準エラーを合流させずに UTF-8 として別々に収集する。
 * プロセス終了と両ストリームの収集完了後に結果を返す。標準入力は使用しない（即座に閉じる）。
 * 上限超過・期限超過・停止要求では子プロセスを停止する。
 */
class ProcessExecutor(private val launcher: ProcessLauncher, private val limits: ExecutionLimits) {

    fun execute(
        executable: Path,
        arguments: List<String>,
        workingDirectory: Path?,
        timeout: Duration,
        cancellation: Cancellation,
    ): ProcessOutcome {
        if (cancellation.isCancelled) return ProcessOutcome.Cancelled
        val process = try {
            launcher.launch(executable, arguments, workingDirectory)
        } catch (e: IOException) {
            return ProcessOutcome.LaunchFailed(e.message ?: e.javaClass.name)
        } catch (e: RuntimeException) {
            return ProcessOutcome.LaunchFailed(e.message ?: e.javaClass.name)
        }
        runCatching { process.outputStream.close() }

        val overflow = AtomicReference<String?>(null)
        val stdout = StreamCollector(process.inputStream, limits.stdoutMaxBytes, "標準出力") {
            overflow.compareAndSet(null, it); terminate(process)
        }
        val stderr = StreamCollector(process.errorStream, limits.stderrMaxBytes, "標準エラー") {
            overflow.compareAndSet(null, it); terminate(process)
        }
        stdout.start()
        stderr.start()

        val registration = cancellation.onCancel { terminate(process) }
        try {
            val exited = try {
                process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                terminate(process)
                return ProcessOutcome.Cancelled
            }
            if (!exited) {
                terminate(process)
                stdout.join(limits.terminateGrace)
                stderr.join(limits.terminateGrace)
                return if (cancellation.isCancelled) ProcessOutcome.Cancelled else ProcessOutcome.TimedOut(timeout)
            }
            // 終了後、両ストリームの収集完了を待つ（孫プロセスがパイプを保持し続ける場合に備え上限付き）。
            val collected = stdout.join(limits.terminateGrace) and stderr.join(limits.terminateGrace)
            if (cancellation.isCancelled) return ProcessOutcome.Cancelled
            overflow.get()?.let { return ProcessOutcome.OutputLimitExceeded(it) }
            if (!collected) return ProcessOutcome.LaunchFailed("プロセス終了後も出力ストリームが閉じられませんでした")
            val out = decode(stdout.bytes()) ?: return ProcessOutcome.MalformedEncoding("標準出力")
            val err = decode(stderr.bytes()) ?: return ProcessOutcome.MalformedEncoding("標準エラー")
            return ProcessOutcome.Completed(process.exitValue(), out, err)
        } finally {
            registration.close()
            if (process.isAlive) terminate(process)
        }
    }

    private fun terminate(process: Process) {
        runCatching { process.descendants().forEach { it.destroy() } }
        process.destroy()
        Thread {
            try {
                if (!process.waitFor(limits.terminateGrace.toMillis(), TimeUnit.MILLISECONDS)) {
                    runCatching { process.descendants().forEach { it.destroyForcibly() } }
                    process.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                process.destroyForcibly()
            }
        }.apply {
            isDaemon = true
            name = "qosctl-terminate"
        }.start()
    }

    private fun decode(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    private class StreamCollector(
        private val input: InputStream,
        private val maxBytes: Int,
        private val label: String,
        private val onOverflow: (String) -> Unit,
    ) {
        private val buffer = ByteArrayOutputStream()
        private val thread = Thread(::pump, "qosctl-$label").apply { isDaemon = true }

        fun start() = thread.start()

        /** 収集完了まで最大 [grace] 待つ。完了したら true。 */
        fun join(grace: Duration): Boolean {
            thread.join(grace.toMillis().coerceAtLeast(1))
            return !thread.isAlive
        }

        @Synchronized
        fun bytes(): ByteArray = buffer.toByteArray()

        private fun pump() {
            val chunk = ByteArray(8192)
            try {
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    synchronized(this) {
                        if (buffer.size() + n > maxBytes) {
                            onOverflow(label)
                            return
                        }
                        buffer.write(chunk, 0, n)
                    }
                }
            } catch (_: IOException) {
                // プロセス停止に伴うストリーム切断。
            } finally {
                runCatching { input.close() }
            }
        }
    }
}
