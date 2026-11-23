package com.technologyfrontier.qide.adapter

import com.technologyfrontier.qide.domain.ErrorCategory
import com.technologyfrontier.qide.domain.QosFailure
import com.technologyfrontier.qide.domain.QosOutcome
import com.technologyfrontier.qide.domain.RunRequest
import com.technologyfrontier.qide.domain.Seed
import com.technologyfrontier.qide.domain.ServiceState
import com.technologyfrontier.qide.domain.Shots
import com.technologyfrontier.qide.domain.Validated
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** T-IDE-07 の応答解釈部分: 3操作の成功、終了値2／3／4、IPC包みの誤受信、破損JSON。 */
class QosctlResponsesTest {
    private val circuit = Path.of("/tmp/bell.json")
    private fun request(shots: Int = 8, seed: String? = null) =
        RunRequest(circuit, Shots.of(shots.toLong())!!, seed?.let { Seed.parse(it)!! })

    private fun ok(stdout: String) = ProcessOutcome.Completed(0, stdout, "")
    private fun err(code: Int, stderr: String) = ProcessOutcome.Completed(code, "", stderr)

    private val designExample =
        """{"counts":[["00",5],["11",3]],"shots":8,"seed":"18446744073709551615","measured_qubits":[0,1],"backend_id":"local_statevector","service_version":"0.1.0"}"""

    private fun integration(outcome: QosOutcome<*>): QosFailure.IntegrationFailure =
        assertIs<QosFailure.IntegrationFailure>(assertIs<QosOutcome.Failure>(outcome).failure)

    @Test
    fun `validate success and non success`() {
        assertEquals(QosOutcome.Success(Validated), QosctlResponses.validate(ok("{\"valid\":true}\n")))
        integration(QosctlResponses.validate(ok("{\"valid\":false}\n")))
        integration(QosctlResponses.validate(ok("{}\n")))
    }

    @Test
    fun `status ready and busy are successes`() {
        assertEquals(QosOutcome.Success(ServiceState.READY), QosctlResponses.status(ok("{\"state\":\"ready\"}\n")))
        assertEquals(QosOutcome.Success(ServiceState.BUSY), QosctlResponses.status(ok("{\"state\":\"busy\"}")))
        integration(QosctlResponses.status(ok("{\"state\":\"degraded\"}")))
    }

    @Test
    fun `run result from design document is accepted`() {
        val result = assertIs<QosOutcome.Success<com.technologyfrontier.qide.domain.RunResult>>(
            QosctlResponses.run(ok(designExample + "\n"), request()),
        ).value
        assertEquals("18446744073709551615", result.seed.decimal)
        assertEquals(listOf(0, 1), result.measuredQubits)
        assertEquals(listOf("00" to 5L, "11" to 3L), result.counts.map { it.bits to it.count })
        assertEquals(0.625, result.ratio(result.counts[0]))
        assertEquals(designExample, result.rawJson)
    }

    @Test
    fun `explicit seed must match and shots must match`() {
        integration(QosctlResponses.run(ok(designExample), request(seed = "0")))
        assertIs<QosOutcome.Success<*>>(QosctlResponses.run(ok(designExample), request(seed = "18446744073709551615")))
        integration(QosctlResponses.run(ok(designExample), request(shots = 1024)))
    }

    @Test
    fun `invalid counts are rejected`() {
        val cases = mapOf(
            "合計不正" to """[["00",5],["11",2]]""",
            "重複" to """[["00",4],["00",4]]""",
            "順序" to """[["11",3],["00",5]]""",
            "キー長" to """[["0",5],["11",3]]""",
            "0/1以外" to """[["0a",5],["11",3]]""",
            "回数0" to """[["00",8],["11",0]]""",
            "負数" to """[["00",9],["11",-1]]""",
            "小数" to """[["00",5.0],["11",3]]""",
            "組でない" to """[["00",5,1],["11",3]]""",
        )
        cases.forEach { (name, counts) ->
            val json = designExample.replace("""[["00",5],["11",3]]""", counts)
            integration(QosctlResponses.run(ok(json), request())).also { assertTrue(it.reason.isNotBlank(), name) }
        }
    }

    @Test
    fun `missing or mistyped fields are rejected`() {
        listOf(
            designExample.replace("\"seed\":\"18446744073709551615\"", "\"seed\":18446744073709551615"),
            designExample.replace("\"seed\":\"18446744073709551615\",", ""),
            designExample.replace("\"seed\":\"18446744073709551615\"", "\"seed\":\"18446744073709551616\""),
            designExample.replace(",\"backend_id\":\"local_statevector\"", ""),
            designExample.replace("\"service_version\":\"0.1.0\"", "\"service_version\":1"),
        ).forEach { integration(QosctlResponses.run(ok(it), request())) }
    }

    @Test
    fun `IPC envelope is not read`() {
        val envelope = """{"protocol_version":1,"ok":true,"result":$designExample}"""
        assertTrue(integration(QosctlResponses.run(ok(envelope), request())).reason.contains("IPC"))
        integration(QosctlResponses.status(ok("""{"ok":true,"result":{"state":"ready"}}""")))
    }

    @Test
    fun `empty, multi line and broken stdout with exit 0 are integration failures`() {
        integration(QosctlResponses.run(ok(""), request()))
        integration(QosctlResponses.run(ok("$designExample\n$designExample\n"), request()))
        integration(QosctlResponses.run(ok(designExample.dropLast(1)), request()))
        integration(QosctlResponses.status(ok("[\"ready\"]")))
    }

    @Test
    fun `exit codes 2 3 4 map to categories by code`() {
        val busy = assertIs<QosFailure.ServiceError>(
            assertIs<QosOutcome.Failure>(QosctlResponses.run(err(3, """{"code":"BUSY","message":"Calculation slot is busy"}""" + "\n"), request())).failure,
        )
        assertEquals(ErrorCategory.ACCESS, busy.category)
        assertTrue(busy.isBusy)
        assertTrue(busy.guidance.contains("再試行しません"))

        val input = assertIs<QosFailure.ServiceError>(
            assertIs<QosOutcome.Failure>(QosctlResponses.validate(err(2, """{"code":"QUBIT_LIMIT","message":"x"}"""))).failure,
        )
        assertEquals(ErrorCategory.INPUT, input.category)
        val exec = assertIs<QosFailure.ServiceError>(
            assertIs<QosOutcome.Failure>(QosctlResponses.status(err(4, """{"code":"DEADLINE","message":"x"}"""))).failure,
        )
        assertEquals(ErrorCategory.EXECUTION, exec.category)
    }

    @Test
    fun `broken error json and unknown exit codes are integration failures`() {
        assertEquals(3, integration(QosctlResponses.run(err(3, "busy"), request())).exitCode)
        integration(QosctlResponses.run(err(2, """{"message":"no code"}"""), request()))
        assertEquals(1, integration(QosctlResponses.run(err(1, """{"code":"X","message":"y"}"""), request())).exitCode)
        assertEquals(137, integration(QosctlResponses.run(err(137, ""), request())).exitCode)
    }

    @Test
    fun `process level failures`() {
        assertEquals(QosOutcome.Cancelled, QosctlResponses.run(ProcessOutcome.Cancelled, request()))
        integration(QosctlResponses.run(ProcessOutcome.TimedOut(Duration.ofSeconds(1)), request()))
        integration(QosctlResponses.run(ProcessOutcome.OutputLimitExceeded("標準出力"), request()))
        integration(QosctlResponses.run(ProcessOutcome.LaunchFailed("no such file"), request()))
        integration(QosctlResponses.run(ProcessOutcome.MalformedEncoding("標準出力"), request()))
    }
}
