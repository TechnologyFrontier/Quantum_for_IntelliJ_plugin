package com.technologyfrontier.qide.integration

import com.technologyfrontier.qide.adapter.ExecutionLimits
import com.technologyfrontier.qide.adapter.ProcessLauncher
import com.technologyfrontier.qide.adapter.QosctlCliClient
import com.technologyfrontier.qide.application.Cancellation
import com.technologyfrontier.qide.application.RerunPlan
import com.technologyfrontier.qide.application.RunCoordinator
import com.technologyfrontier.qide.application.RunState
import com.technologyfrontier.qide.domain.CircuitStructure
import com.technologyfrontier.qide.domain.ErrorCategory
import com.technologyfrontier.qide.domain.QosFailure
import com.technologyfrontier.qide.domain.QosOutcome
import com.technologyfrontier.qide.domain.RunRequest
import com.technologyfrontier.qide.domain.RunResult
import com.technologyfrontier.qide.domain.Seed
import com.technologyfrontier.qide.domain.ServiceState
import com.technologyfrontier.qide.domain.Shots
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 実際の量子OS CLI（qosctl）と量子サービス（qosd）を相手にした結合試験。
 *
 * 環境変数:
 * - QIDE_QOSCTL: qosctl の絶対パス
 * - QOS_SOCKET: 利用許可された qosd のソケット（qosctl が参照）
 * - QIDE_QOS_SOCKET_DENIED: 利用者を許可しない qosd のソケット（ACCESS_DENIED 確認用、任意）
 *
 * `scripts/real-qosctl-it.sh` がサービスを起動してこの試験を実行する。
 */
@Tag("real-qosctl")
@EnabledIfEnvironmentVariable(named = "QIDE_QOSCTL", matches = ".+")
class RealQosctlIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private val qosctl: Path = Path.of(System.getenv("QIDE_QOSCTL") ?: "/usr/bin/qosctl")
    private val pool = Executors.newCachedThreadPool()

    @AfterTest
    fun tearDown() {
        pool.shutdownNow()
        awaitReady()
    }

    private fun launcherWithSocket(socket: String) = ProcessLauncher { exe, args, wd ->
        ProcessBuilder(listOf(exe.toString()) + args)
            .apply { wd?.let { directory(it.toFile()) } }
            .apply { environment()["QOS_SOCKET"] = socket }
            .start()
    }

    private fun client(limits: ExecutionLimits = ExecutionLimits()) =
        QosctlCliClient(qosctl, dir, com.technologyfrontier.qide.adapter.JdkProcessLauncher(), limits)

    private fun circuit(name: String, json: String): Path =
        dir.resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, json) }

    private val bell = """{"schema_version":1,"n_qubits":2,"operations":[{"op":"h","qubits":[0]},{"op":"cx","qubits":[0,1]},{"op":"measure","qubits":[0,1]}]}"""

    /** 20量子ビットに 9,999 個の H を掛ける重い回路（この環境で約40秒）。 */
    private fun slowCircuit(): Path {
        val ops = (0 until 9_999).joinToString(",") { """{"op":"h","qubits":[${it % 20}]}""" }
        return circuit("slow20.circuit.json", """{"schema_version":1,"n_qubits":20,"operations":[$ops,{"op":"measure","qubits":[${(0 until 20).joinToString(",")}]}]}""")
    }

    private fun run(path: Path, shots: Int = 1024, seed: String? = null, c: QosctlCliClient = client()): QosOutcome<RunResult> =
        c.run(RunRequest(path, Shots.of(shots.toLong())!!, seed?.let { Seed.parse(it)!! }), Cancellation())

    private fun success(outcome: QosOutcome<RunResult>): RunResult =
        (outcome as? QosOutcome.Success)?.value ?: fail("成功を期待しましたが $outcome")

    private fun serviceError(outcome: QosOutcome<*>): QosFailure.ServiceError =
        assertIs<QosFailure.ServiceError>(assertIs<QosOutcome.Failure>(outcome, "$outcome").failure)

    private fun status(): QosOutcome<ServiceState> = client().status(Cancellation())

    private fun awaitStatus(expected: ServiceState, timeout: Duration = Duration.ofSeconds(30)): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (status() == QosOutcome.Success(expected)) return true
            Thread.sleep(200)
        }
        return false
    }

    private fun awaitReady() {
        awaitStatus(ServiceState.READY, Duration.ofSeconds(60))
    }

    @Test
    fun `status is ready`() {
        assertEquals(QosOutcome.Success(ServiceState.READY), status())
    }

    /** T-IDE-02: ベル回路 1,024 ショットのキーが 00・11 の範囲内で合計が 1,024。 */
    @Test
    fun `bell circuit from design document`() {
        val r = success(run(circuit("bell.circuit.json", bell), 1024, "0"))
        assertTrue(r.counts.all { it.bits == "00" || it.bits == "11" }, r.counts.toString())
        assertEquals(1024L, r.counts.sumOf { it.count })
        assertEquals(listOf(0, 1), r.measuredQubits)
        assertEquals("local_statevector", r.backendId)
    }

    /** QOS-IF の例: X(1)→measure(1,0) は "10"（ビット順は measured_qubits 順）。 */
    @Test
    fun `bit order follows measured_qubits`() {
        val path = circuit("x1.circuit.json", """{"schema_version":1,"n_qubits":2,"operations":[{"op":"x","qubits":[1]},{"op":"measure","qubits":[1,0]}]}""")
        val r = success(run(path, 8, "0"))
        assertEquals(listOf(1, 0), r.measuredQubits)
        assertEquals(listOf("10" to 8L), r.counts.map { it.bits to it.count })
    }

    /** T-IDE-06: 空白・日本語を含むパス。 */
    @Test
    fun `path with spaces and Japanese`() {
        val path = circuit("My Circuits/ベル回路.circuit.json", bell)
        assertIs<QosOutcome.Success<*>>(client().validate(path, Cancellation()))
        success(run(path, 16))
    }

    /** T-IDE-03: 0・2⁵³＋1・2⁶⁴−1 のシードを保持し、同一条件で観測回数が一致する。 */
    @Test
    fun `seed boundaries round trip and reproduce`() {
        val path = circuit("bell.circuit.json", bell)
        listOf("0", "9007199254740993", "18446744073709551615").forEach { seed ->
            val first = success(run(path, 1000, seed))
            assertEquals(seed, first.seed.decimal)
            val second = success(run(path, 1000, seed))
            assertEquals(first.counts, second.counts, "seed $seed で再現しない")
        }
    }

    /** シード省略時はサービスが選んだシードが返り、それを使った再実行が一致する（結果ペインの再実行）。 */
    @Test
    fun `omitted seed then rerun with returned seed`() {
        val path = circuit("superposition.circuit.json", """{"schema_version":1,"n_qubits":1,"operations":[{"op":"h","qubits":[0]},{"op":"measure","qubits":[0]}]}""")
        val coordinator = RunCoordinator({ client() }, pool)
        coordinator.start(RunRequest(path, Shots.of(500)!!, null), "d")
        val first = awaitTerminal(coordinator) as RunState.Succeeded
        val plan = RerunPlan.from(first, "d")
        assertEquals(first.result.seed, plan.request.seed)
        val again = success(client().run(plan.request, Cancellation()))
        assertEquals(first.result.counts, again.counts)
    }

    /** T-IDE-02: 20／21 量子ビットの境界。validate は上限を見ず、run が BACKEND_LIMIT（終了値 2）。 */
    @Test
    fun `20 and 21 qubit boundary`() {
        fun wide(n: Int) = circuit("w$n.circuit.json", """{"schema_version":1,"n_qubits":$n,"operations":[{"op":"x","qubits":[${n - 1}]},{"op":"measure","qubits":[${(0 until n).joinToString(",")}]}]}""")
        val ok = success(run(wide(20), 4, "1"))
        assertEquals("0".repeat(19) + "1", ok.counts.single().bits)
        val w21 = wide(21)
        assertIs<QosOutcome.Success<*>>(client().validate(w21, Cancellation()))
        val e = serviceError(run(w21, 4))
        assertEquals(ErrorCategory.INPUT, e.category)
        assertEquals("BACKEND_LIMIT", e.code)
    }

    /** T-IDE-01: 不正な測定・未知版は CLI が拒否（終了値 2）。 */
    @Test
    fun `invalid measure and unknown schema are rejected`() {
        val badMeasure = circuit("bad.circuit.json", """{"schema_version":1,"n_qubits":1,"operations":[{"op":"measure","qubits":[0]},{"op":"x","qubits":[0]}]}""")
        assertEquals("CIRCUIT_INVALID", serviceError(client().validate(badMeasure, Cancellation())).code)
        val v2 = circuit("v2.circuit.json", """{"schema_version":2,"n_qubits":1,"operations":[{"op":"measure","qubits":[0]}]}""")
        val e = serviceError(client().validate(v2, Cancellation()))
        assertEquals("SCHEMA_UNSUPPORTED", e.code)
        assertEquals(ErrorCategory.INPUT, e.category)
    }

    /** エディターの構造チェックとサービスの validate の判定が一致すること（補助としての整合）。 */
    @Test
    fun `local structure check agrees with service validate`() {
        val cases = listOf(
            bell,
            """{"schema_version":1,"n_qubits":1,"operations":[{"op":"rx","qubits":[0],"theta":0.5},{"op":"measure","qubits":[0]}]}""",
            """{"schema_version":1,"n_qubits":1,"operations":[{"op":"rx","qubits":[0]},{"op":"measure","qubits":[0]}]}""",
            """{"schema_version":1,"n_qubits":1,"operations":[{"op":"h","qubits":[0],"theta":1},{"op":"measure","qubits":[0]}]}""",
            """{"schema_version":1,"n_qubits":2,"operations":[{"op":"measure","qubits":[0]},{"op":"measure","qubits":[1]}]}""",
            """{"schema_version":1,"n_qubits":1,"operations":[{"op":"h","qubits":[0]}]}""",
            """{"schema_version":1,"n_qubits":1,"operations":[]}""",
            """{"schema_version":1,"n_qubits":1,"operations":[{"op":"measure","qubits":[0]}],"seed":"0"}""",
            """{"schema_version":1,"n_qubits":2,"operations":[{"op":"cx","qubits":[0,0]},{"op":"measure","qubits":[0]}]}""",
            """{"schema_version":1,"n_qubits":2,"operations":[{"op":"cx","qubits":[0]},{"op":"measure","qubits":[0]}]}""",
            """{"schema_version":1,"n_qubits":1,"operations":[{"op":"x","qubits":[1]},{"op":"measure","qubits":[0]}]}""",
            """{"schema_version":1,"n_qubits":0,"operations":[{"op":"measure","qubits":[0]}]}""",
            """{"schema_version":1,"n_qubits":65,"operations":[{"op":"measure","qubits":[0]}]}""",
            """{"schema_version":1,"n_qubits":64,"operations":[{"op":"measure","qubits":[63]}]}""",
            """{"schema_version":1,"n_qubits":true,"operations":[{"op":"measure","qubits":[0]}]}""",
            """{"schema_version":"1","n_qubits":1,"operations":[{"op":"measure","qubits":[0]}]}""",
            """{"schema_version":1,"n_qubits":1,"operations":[{"op":"y","qubits":[0]},{"op":"measure","qubits":[0]}]}""",
            """{"schema_version":1,"n_qubits":1,"operations":[{"op":"measure","qubits":[]}]}""",
        )
        cases.forEachIndexed { i, json ->
            val localOk = CircuitStructure.check(json).none { it.severity == CircuitStructure.Severity.ERROR }
            val serviceOk = client().validate(circuit("c$i.circuit.json", json), Cancellation()) is QosOutcome.Success
            assertEquals(serviceOk, localOk, "判定が一致しません: $json")
        }
    }

    /** T-IDE-02: 計算中の BUSY（終了値 3、再試行しない）、IDE からの停止、停止後の結果を採用しない。 */
    @Test
    fun `busy while running, stop releases the slot and late result is ignored`() {
        val coordinator = RunCoordinator({ client() }, pool)
        coordinator.start(RunRequest(slowCircuit(), Shots.of(10)!!, Seed.parse("1")), null)
        assertTrue(awaitStatus(ServiceState.BUSY, Duration.ofSeconds(20)), "サービスが busy になりません")

        val busy = serviceError(run(circuit("bell.circuit.json", bell), 8))
        assertTrue(busy.isBusy)
        assertEquals(ErrorCategory.ACCESS, busy.category)

        assertTrue(coordinator.stop())
        assertTrue(coordinator.state is RunState.Stopped)
        // CLI が止まり接続が切れると、サービスは計算を停止して計算枠を解放する。
        assertTrue(awaitStatus(ServiceState.READY, Duration.ofSeconds(20)), "停止後に計算枠が解放されません")
        Thread.sleep(500)
        assertTrue(coordinator.state is RunState.Stopped)
        assertNull(coordinator.lastSuccess)
        success(run(circuit("bell.circuit.json", bell), 8))
    }

    /** IDE 側の監視期限を超えたら子プロセスを停止し、連携失敗とする。サービスの計算枠も解放される。 */
    @Test
    fun `ide side timeout stops qosctl`() {
        val outcome = client(ExecutionLimits(runTimeout = Duration.ofSeconds(2), terminateGrace = Duration.ofSeconds(1)))
            .run(RunRequest(slowCircuit(), Shots.of(10)!!, null), Cancellation())
        val failure = assertIs<QosFailure.IntegrationFailure>(assertIs<QosOutcome.Failure>(outcome).failure)
        assertTrue(failure.reason.contains("監視期限"), failure.reason)
        assertTrue(awaitStatus(ServiceState.READY, Duration.ofSeconds(20)), "期限超過後に計算枠が解放されません")
    }

    /** T-IDE-04: サービス停止（接続不能）は終了値 3・SERVICE_UNAVAILABLE。 */
    @Test
    fun `service unavailable`() {
        val c = QosctlCliClient(qosctl, dir, launcherWithSocket(dir.resolve("none.sock").toString()))
        val e = serviceError(c.status(Cancellation()))
        assertEquals("SERVICE_UNAVAILABLE", e.code)
        assertEquals(ErrorCategory.ACCESS, e.category)
    }

    /** T-IDE-04: 権限不許可は終了値 3・ACCESS_DENIED（サービス停止と区別できる）。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "QIDE_QOS_SOCKET_DENIED", matches = ".+")
    fun `access denied`() {
        val c = QosctlCliClient(qosctl, dir, launcherWithSocket(System.getenv("QIDE_QOS_SOCKET_DENIED")))
        val e = serviceError(c.status(Cancellation()))
        assertEquals("ACCESS_DENIED", e.code)
        assertEquals(ErrorCategory.ACCESS, e.category)
        assertTrue(e.guidance.contains("quantum グループ"))
    }

    /** 不正な shots は CLI がサービス接続前に拒否する（IDE は起動前に拒否するが、CLI 側の契約も確認）。 */
    @Test
    fun `cli rejects invalid seed text`() {
        val c = QosctlCliClient(qosctl, dir, ProcessLauncher { exe, _, wd ->
            ProcessBuilder(exe.toString(), "run", dir.resolve("bell.circuit.json").toString(), "--seed", "01")
                .apply { wd?.let { directory(it.toFile()) } }.start()
        })
        circuit("bell.circuit.json", bell)
        assertEquals("RUN_OPTION_INVALID", serviceError(c.status(Cancellation())).code)
    }

    private fun awaitTerminal(coordinator: RunCoordinator): RunState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            val s = coordinator.state
            if (s !is RunState.Running && s !is RunState.Idle) return s
            Thread.sleep(50)
        }
        fail("実行が終わりません")
    }
}
