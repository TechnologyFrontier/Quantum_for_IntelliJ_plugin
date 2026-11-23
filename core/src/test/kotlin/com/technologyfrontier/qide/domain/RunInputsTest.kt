package com.technologyfrontier.qide.domain

import com.technologyfrontier.qide.adapter.QosctlArguments
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** T-IDE-03: シードの境界値（0・2⁵³＋1・2⁶⁴−1）を文字列のまま保持し、上限超過は起動前に拒否する。 */
class RunInputsTest {
    @Test
    fun `seed boundaries are kept exactly`() {
        listOf("0", "9007199254740993", "18446744073709551615").forEach {
            assertEquals(it, assertNotNull(Seed.parse(it)).decimal)
        }
    }

    @Test
    fun `seed rejects out of range and non canonical text`() {
        listOf("18446744073709551616", "-1", "+1", "01", "00", " 1", "1.0", "1e3", "0x10", "１", "").forEach {
            assertNull(Seed.parse(it), it)
        }
    }

    @Test
    fun `seed input blank means omitted and zero is explicit`() {
        assertEquals(InputCheck.Ok(null), RunInputValidator.seed("  "))
        val zero = assertIs<InputCheck.Ok<Seed?>>(RunInputValidator.seed("0"))
        assertEquals("0", zero.value?.decimal)
        assertIs<InputCheck.Invalid>(RunInputValidator.seed("18446744073709551616"))
    }

    @Test
    fun `shots range and default`() {
        assertEquals(InputCheck.Ok(Shots.DEFAULT), RunInputValidator.shots(""))
        assertEquals(1, (RunInputValidator.shots("1") as InputCheck.Ok).value.value)
        assertEquals(100_000, (RunInputValidator.shots("100000") as InputCheck.Ok).value.value)
        listOf("0", "100001", "-5", "1.5", "abc", "0100").forEach {
            assertIs<InputCheck.Invalid>(RunInputValidator.shots(it), it)
        }
    }

    /** T-IDE-06: 空白・日本語を含むパスとシードの省略／ゼロを引数単位で確認。 */
    @Test
    fun `argument arrays follow the contract`() {
        val circuit = Path.of("/home/user/My Circuits/ベル回路.json")
        assertEquals(listOf("validate", "/home/user/My Circuits/ベル回路.json"), QosctlArguments.validate(circuit))
        assertEquals(listOf("status"), QosctlArguments.status())
        assertEquals(
            listOf("run", "/home/user/My Circuits/ベル回路.json", "--shots", "1024"),
            QosctlArguments.run(RunRequest(circuit, Shots.DEFAULT, null)),
        )
        assertEquals(
            listOf("run", "/home/user/My Circuits/ベル回路.json", "--shots", "1024", "--seed", "0"),
            QosctlArguments.run(RunRequest(circuit, Shots.DEFAULT, Seed.parse("0"))),
        )
    }
}
