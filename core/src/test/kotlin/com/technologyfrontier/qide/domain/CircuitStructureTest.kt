package com.technologyfrontier.qide.domain

import com.technologyfrontier.qide.samples.Sample
import com.technologyfrontier.qide.samples.SampleNaming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircuitStructureTest {
    private fun errors(text: String) = CircuitStructure.check(text).filter { it.severity == CircuitStructure.Severity.ERROR }

    @Test
    fun `bundled samples have no findings`() {
        Sample.entries.forEach { sample ->
            val text = javaClass.getResourceAsStream("/samples/${sample.baseName}.circuit.json")!!.readBytes()
            assertEquals(emptyList(), CircuitStructure.check(text), sample.name)
        }
    }

    @Test
    fun `reports version, qubit count and unknown op`() {
        val findings = errors("""{"schema_version":2,"n_qubits":65,"operations":[{"op":"y","qubits":[0]}]}""")
        assertEquals(setOf("/schema_version", "/n_qubits", "/operations/0/op"), findings.map { it.path }.toSet())
    }

    @Test
    fun `gate after measurement is an error`() {
        val f = errors("""{"schema_version":1,"n_qubits":1,"operations":[{"op":"measure","qubits":[0]},{"op":"x","qubits":[0]}]}""")
        assertEquals(listOf("/operations/1"), f.map { it.path })
    }

    @Test
    fun `arity, range and duplicates`() {
        val f = errors(
            """{"schema_version":1,"n_qubits":2,"operations":[
              {"op":"cx","qubits":[0]},{"op":"cx","qubits":[1,1]},{"op":"h","qubits":[2]},{"op":"measure","qubits":[0,1]}]}""",
        )
        assertEquals(listOf("/operations/0/qubits", "/operations/1/qubits", "/operations/2/qubits"), f.map { it.path })
    }

    @Test
    fun `more than 20 qubits warns about run limit`() {
        val w = CircuitStructure.check("""{"schema_version":1,"n_qubits":21,"operations":[{"op":"measure","qubits":[0]}]}""")
        assertEquals(listOf(CircuitStructure.Severity.WARNING), w.map { it.severity })
        assertTrue(CircuitStructure.check("""{"schema_version":1,"n_qubits":20,"operations":[{"op":"measure","qubits":[0]}]}""").isEmpty())
    }

    @Test
    fun `operation count and size limits`() {
        val ops = (1..10_001).joinToString(",") { """{"op":"measure","qubits":[0]}""" }
        val f = errors("""{"schema_version":1,"n_qubits":1,"operations":[$ops]}""")
        assertEquals(listOf("/operations"), f.map { it.path })
        val big = ByteArray((4 * 1024 * 1024) + 1) { ' '.code.toByte() }
        assertEquals(1, CircuitStructure.check(big).size)
    }

    @Test
    fun `sample naming never overwrites`() {
        val existing = setOf("bell.circuit.json", "bell-2.md")
        val names = SampleNaming.choose(Sample.BELL) { it in existing }
        assertEquals("bell-3.circuit.json", names.circuit)
        assertEquals("bell-3.md", names.guide)
        assertEquals("bell.circuit.json", SampleNaming.choose(Sample.BELL) { false }.circuit)
    }
}
