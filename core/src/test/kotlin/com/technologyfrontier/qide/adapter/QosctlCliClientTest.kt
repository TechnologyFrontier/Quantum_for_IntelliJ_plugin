package com.technologyfrontier.qide.adapter

import com.technologyfrontier.qide.application.Cancellation
import com.technologyfrontier.qide.domain.QosFailure
import com.technologyfrontier.qide.domain.QosOutcome
import com.technologyfrontier.qide.domain.RunRequest
import com.technologyfrontier.qide.domain.RunResult
import com.technologyfrontier.qide.domain.Seed
import com.technologyfrontier.qide.domain.ServiceState
import com.technologyfrontier.qide.domain.Shots
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ダミー CLI による qosctl-adapter の契約試験（機能設計書 6.1・8.1 T-IDE-06／07）。
 * 実プロセスを起動し、標準出力・標準エラー・終了値・引数の受け渡しを検証する。
 */
class QosctlCliClientTest {
    @TempDir
    lateinit var dir: Path

    private val bellJson = """{"counts":[["00",520],["11",504]],"shots":1024,"seed":"0","measured_qubits":[0,1],"backend_id":"local_statevector","service_version":"0.1.0"}"""

    /** 引数を1行ずつ args.txt に記録してから [body] を実行するダミー qosctl を作る。 */
    private fun fakeQosctl(body: String): Path {
        val script = dir.resolve("qosctl")
        Files.writeString(
            script,
            "#!/bin/sh\nfor a in \"\$@\"; do printf '%s\\n' \"\$a\"; done > '${dir.resolve("args.txt")}'\n$body\n",
        )
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"))
        return script
    }

    private fun recordedArgs(): List<String> = Files.readAllLines(dir.resolve("args.txt"))

    private fun circuit(name: String = "My Circuits/ベル回路.json"): Path {
        val file = dir.resolve(name)
        Files.createDirectories(file.parent)
        Files.writeString(file, "{}")
        return file
    }

    private fun client(script: Path, limits: ExecutionLimits = ExecutionLimits()) =
        QosctlCliClient(script, dir, JdkProcessLauncher(), limits)

    @Test
    fun `run passes path with spaces and Japanese as one argument and omits seed`() {
        val script = fakeQosctl("printf '%s\\n' '$bellJson'")
        val path = circuit()
        val outcome = client(script).run(RunRequest(path, Shots.DEFAULT, null), Cancellation())
        assertIs<QosOutcome.Success<RunResult>>(outcome)
        assertEquals(listOf("run", path.toString(), "--shots", "1024"), recordedArgs())
    }

    @Test
    fun `explicit zero seed is passed`() {
        val script = fakeQosctl("printf '%s\\n' '$bellJson'")
        val path = circuit()
        val outcome = client(script).run(RunRequest(path, Shots.DEFAULT, Seed.parse("0")), Cancellation())
        assertEquals("0", assertIs<QosOutcome.Success<RunResult>>(outcome).value.seed.decimal)
        assertEquals(listOf("run", path.toString(), "--shots", "1024", "--seed", "0"), recordedArgs())
    }

    @Test
    fun `validate and status`() {
        val path = circuit("bell.json")
        assertIs<QosOutcome.Success<*>>(client(fakeQosctl("echo '{\"valid\":true}'")).validate(path, Cancellation()))
        assertEquals(listOf("validate", path.toString()), recordedArgs())
        assertEquals(QosOutcome.Success(ServiceState.BUSY), client(fakeQosctl("echo '{\"state\":\"busy\"}'")).status(Cancellation()))
        assertEquals(listOf("status"), recordedArgs())
    }

    @Test
    fun `stderr is not merged into stdout`() {
        val script = fakeQosctl("echo 'warning on stderr' >&2\nprintf '%s\\n' '{\"state\":\"ready\"}'")
        assertEquals(QosOutcome.Success(ServiceState.READY), client(script).status(Cancellation()))
    }

    @Test
    fun `exit code 3 BUSY is reported from stderr json`() {
        val script = fakeQosctl("echo '{\"code\":\"BUSY\",\"message\":\"Calculation slot is busy\"}' >&2\nexit 3")
        val failure = assertIs<QosOutcome.Failure>(client(script).run(RunRequest(circuit(), Shots.DEFAULT, null), Cancellation())).failure
        assertTrue(assertIs<QosFailure.ServiceError>(failure).isBusy)
    }

    @Test
    fun `missing executable is an integration failure`() {
        val outcome = QosctlCliClient(dir.resolve("nope"), dir).status(Cancellation())
        assertIs<QosFailure.IntegrationFailure>(assertIs<QosOutcome.Failure>(outcome).failure)
    }

    @Test
    fun `output limit stops the process`() {
        val script = fakeQosctl("yes 0000000000 | head -c 200000\nexit 0")
        val outcome = client(script, ExecutionLimits(stdoutMaxBytes = 1000)).status(Cancellation())
        val failure = assertIs<QosFailure.IntegrationFailure>(assertIs<QosOutcome.Failure>(outcome).failure)
        assertTrue(failure.reason.contains("上限"), failure.reason)
    }

    @Test
    fun `timeout stops the process`() {
        val script = fakeQosctl("sleep 30")
        val started = System.nanoTime()
        val outcome = client(script, ExecutionLimits(statusTimeout = Duration.ofMillis(300), terminateGrace = Duration.ofMillis(500)))
            .status(Cancellation())
        val failure = assertIs<QosFailure.IntegrationFailure>(assertIs<QosOutcome.Failure>(outcome).failure)
        assertTrue(failure.reason.contains("監視期限"), failure.reason)
        assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 10)
    }

    @Test
    fun `cancellation stops the process and late output is not adopted`() {
        // 停止要求の後に結果を出力しようとするダミー CLI。
        val script = fakeQosctl("trap '' TERM\nsleep 1\nprintf '%s\\n' '$bellJson'")
        val cancellation = Cancellation()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<QosOutcome<RunResult>> {
                client(script, ExecutionLimits(terminateGrace = Duration.ofMillis(200)))
                    .run(RunRequest(circuit(), Shots.DEFAULT, Seed.parse("0")), cancellation)
            }
            Thread.sleep(200)
            cancellation.cancel()
            assertEquals(QosOutcome.Cancelled, future.get(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `invalid utf8 output is an integration failure`() {
        val script = fakeQosctl("printf '\\377\\376\\n'")
        assertIs<QosFailure.IntegrationFailure>(assertIs<QosOutcome.Failure>(client(script).status(Cancellation())).failure)
    }
}
