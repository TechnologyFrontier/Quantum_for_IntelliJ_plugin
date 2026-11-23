package com.technologyfrontier.qide.application

import com.technologyfrontier.qide.domain.CountEntry
import com.technologyfrontier.qide.domain.ErrorCategory
import com.technologyfrontier.qide.domain.QosFailure
import com.technologyfrontier.qide.domain.QosOutcome
import com.technologyfrontier.qide.domain.RunRequest
import com.technologyfrontier.qide.domain.RunResult
import com.technologyfrontier.qide.domain.Seed
import com.technologyfrontier.qide.domain.ServiceState
import com.technologyfrontier.qide.domain.Shots
import com.technologyfrontier.qide.domain.Validated
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** QosctlClient の代替実装で状態遷移を確認する（機能設計書 6.1）。 */
class RunCoordinatorTest {
    private val pool = Executors.newCachedThreadPool()

    @AfterTest
    fun tearDown() {
        pool.shutdownNow()
    }

    private val request = RunRequest(Path.of("/tmp/bell.json"), Shots.DEFAULT, Seed.parse("7"))

    private fun result(seed: String = "7") = RunResult(
        listOf(CountEntry("00", 1000), CountEntry("11", 24)), 1024, Seed.parse(seed)!!, listOf(0, 1), "local_statevector", "0.1.0", "{}",
    )

    /** run が [release] されるまでブロックし、[outcome] を返す代替クライアント。 */
    private class FakeClient(var outcome: QosOutcome<RunResult>) : QosctlClient {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        var sawCancellation = false
        override fun validate(circuit: Path, cancellation: Cancellation): QosOutcome<Validated> = QosOutcome.Success(Validated)
        override fun status(cancellation: Cancellation): QosOutcome<ServiceState> = QosOutcome.Success(ServiceState.READY)
        override fun run(request: RunRequest, cancellation: Cancellation): QosOutcome<RunResult> {
            cancellation.onCancel { sawCancellation = true }
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            return outcome
        }
    }

    private fun awaitState(coordinator: RunCoordinator, predicate: (RunState) -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate(coordinator.state)) {
            check(System.nanoTime() < deadline) { "timeout: ${coordinator.state}" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `idle to running to succeeded, finalized once`() {
        val client = FakeClient(QosOutcome.Success(result()))
        val coordinator = RunCoordinator({ client }, pool)
        val states = CopyOnWriteArrayList<RunState>()
        coordinator.addListener { states += it }
        val finished = CopyOnWriteArrayList<RunState>()
        assertIs<RunCoordinator.StartResult.Started>(coordinator.start(request, "d1") { finished += it })
        assertIs<RunState.Running>(coordinator.state)
        client.release.countDown()
        awaitState(coordinator) { it is RunState.Succeeded }
        assertEquals(1, finished.size)
        assertEquals(listOf(RunState.Running::class, RunState.Succeeded::class), states.map { it::class })
        assertSame<Any?>(coordinator.state, coordinator.lastSuccess)
    }

    @Test
    fun `only one run per project`() {
        val client = FakeClient(QosOutcome.Success(result()))
        val coordinator = RunCoordinator({ client }, pool)
        coordinator.start(request, null)
        assertEquals(RunCoordinator.StartResult.AlreadyRunning, coordinator.start(request, null))
        client.release.countDown()
        awaitState(coordinator) { it is RunState.Succeeded }
        assertIs<RunCoordinator.StartResult.Started>(coordinator.start(request, null))
    }

    @Test
    fun `stop finalizes immediately and late result is ignored`() {
        val client = FakeClient(QosOutcome.Success(result()))
        val coordinator = RunCoordinator({ client }, pool)
        val finished = CopyOnWriteArrayList<RunState>()
        coordinator.start(request, null) { finished += it }
        assertTrue(client.entered.await(5, TimeUnit.SECONDS))
        assertTrue(coordinator.stop())
        assertTrue(coordinator.state is RunState.Stopped)
        assertTrue(client.sawCancellation, "CLI へ停止が伝わること")
        client.release.countDown() // 停止後に結果が届く
        Thread.sleep(100)
        assertTrue(coordinator.state is RunState.Stopped)
        assertNull(coordinator.lastSuccess)
        assertEquals(1, finished.size)
        assertFalse(coordinator.stop())
    }

    @Test
    fun `failure keeps previous successful result and busy is not retried`() {
        val client = FakeClient(QosOutcome.Success(result()))
        var calls = 0
        val coordinator = RunCoordinator({ calls++; client }, pool)
        client.release.countDown()
        coordinator.start(request, null)
        awaitState(coordinator) { it is RunState.Succeeded }
        val success = coordinator.lastSuccess

        client.outcome = QosOutcome.Failure(QosFailure.ServiceError(ErrorCategory.ACCESS, "BUSY", "busy"))
        coordinator.start(request, null)
        awaitState(coordinator) { it is RunState.Failed }
        Thread.sleep(100)
        assertEquals(2, calls, "BUSY で再試行しない")
        assertSame(success, coordinator.lastSuccess)
    }

    @Test
    fun `after dispose nothing is reflected`() {
        val client = FakeClient(QosOutcome.Success(result()))
        val coordinator = RunCoordinator({ client }, pool)
        val states = CopyOnWriteArrayList<RunState>()
        coordinator.addListener { states += it }
        coordinator.start(request, null)
        assertTrue(client.entered.await(5, TimeUnit.SECONDS))
        coordinator.dispose()
        assertTrue(client.sawCancellation, "プロジェクト終了で子プロセスを停止")
        client.release.countDown()
        Thread.sleep(100)
        assertEquals(1, states.size)
        assertEquals(RunCoordinator.StartResult.Disposed, coordinator.start(request, null))
    }

    @Test
    fun `rerun plan adopts seed and shots and detects circuit change`() {
        val client = FakeClient(QosOutcome.Success(result(seed = "18446744073709551615")))
        client.release.countDown()
        val coordinator = RunCoordinator({ client }, pool)
        coordinator.start(RunRequest(request.circuit, Shots.DEFAULT, null), "digest-a")
        awaitState(coordinator) { it is RunState.Succeeded }
        val succeeded = coordinator.lastSuccess!!

        val same = RerunPlan.from(succeeded, "digest-a")
        assertFalse(same.circuitChanged)
        assertEquals("18446744073709551615", same.request.seed?.decimal)
        assertEquals(1024, same.request.shots.value)
        assertTrue(RerunPlan.from(succeeded, "digest-b").circuitChanged)
        assertTrue(RerunPlan.from(succeeded, null).circuitChanged)
    }

    @Test
    fun `digest is stable`() {
        assertEquals(CircuitDigest.of("abc".toByteArray()), CircuitDigest.of("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", CircuitDigest.of("abc".toByteArray()))
    }
}
