package net.hwyz.iov.vehicle.ivi.ivai.agenttest.repository

import java.io.File
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.TestSuiteFixtures
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.error.TestErrorCode
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.SuiteFingerprint
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.import.SuiteImportException
import net.hwyz.iov.vehicle.ivi.ivai.agenttest.model.ActiveSuiteDescriptor
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * CR-015 激活存储单测：原子写入、写后 Hash 校验、失败保留上一份、损坏回退。
 */
class ActiveSuiteStoreTest {

    @TempDir
    lateinit var tempDir: File

    private fun store() = ActiveSuiteStore(File(tempDir, "active"))

    private fun descriptor(bytes: ByteArray, suiteId: String = "suite-test-v1", caseCount: Int = 2) =
        ActiveSuiteDescriptor(
            suiteId = suiteId,
            schemaVersion = 1,
            governanceVersion = "ivai-governance-v1-draft",
            caseCount = caseCount,
            sha256 = SuiteFingerprint.sha256(bytes),
            importedAtEpochMs = 1234L
        )

    @Test
    fun `激活成功后文件与描述符可读且内容一致`() {
        val s = store()
        val bytes = TestSuiteFixtures.validSuiteRaw.toByteArray()
        val d = descriptor(bytes)
        s.activate(bytes, d)

        val read = s.current()
        assertTrue(read is ActiveStoreRead.Snapshot)
        val snapshot = (read as ActiveStoreRead.Snapshot).snapshot
        assertEquals(d, snapshot.descriptor)
        assertArrayEquals(bytes, snapshot.bytes)
        // 文件位于应用私有目录子路径。
        assertTrue(File(tempDir, "active/agent-regression.json").exists())
        assertTrue(File(tempDir, "active/active-suite.json").exists())
        // 临时文件已清理。
        assertFalse(File(tempDir, "active/agent-regression.json.tmp").exists())
        assertFalse(File(tempDir, "active/active-suite.json.tmp").exists())
    }

    @Test
    fun `写入后 Hash 不一致 → IMPORT-006 且上一份保持可读`() {
        val s = store()
        val first = "first suite content".toByteArray()
        s.activate(first, descriptor(first, suiteId = "first-suite", caseCount = 1))

        // 第二次激活：内容与描述符声称的 Hash 不一致。
        val second = "second suite content".toByteArray()
        val bad = descriptor(second).copy(sha256 = "deadbeef")
        val e = assertThrows(SuiteImportException::class.java) { s.activate(second, bad) }
        assertEquals(TestErrorCode.IMPORT_ACTIVATION_FAILED, e.errorCode)

        // 上一份激活 Suite 保持可读。
        val read = s.current()
        assertTrue(read is ActiveStoreRead.Snapshot)
        val snapshot = (read as ActiveStoreRead.Snapshot).snapshot
        assertArrayEquals(first, snapshot.bytes)
        assertEquals("first-suite", snapshot.descriptor.suiteId)
        // 临时文件被清理。
        assertFalse(File(tempDir, "active/agent-regression.json.tmp").exists())
    }

    @Test
    fun `激活目录被文件占位 → IMPORT-006`() {
        // activeDir 位置被普通文件占位 → mkdirs 失败 → 写入失败。
        val blocker = File(tempDir, "active")
        blocker.writeText("i am a file, not a dir")
        val s = ActiveSuiteStore(blocker)
        val e = assertThrows(SuiteImportException::class.java) {
            s.activate("x".toByteArray(), descriptor("x".toByteArray()))
        }
        assertEquals(TestErrorCode.IMPORT_ACTIVATION_FAILED, e.errorCode)
    }

    @Test
    fun `无激活文件时 current 返回 Absent`() {
        assertTrue(store().current() is ActiveStoreRead.Absent)
    }

    @Test
    fun `描述符损坏时 current 返回 Corrupt`() {
        val s = store()
        val bytes = TestSuiteFixtures.validSuiteRaw.toByteArray()
        s.activate(bytes, descriptor(bytes))
        File(tempDir, "active/active-suite.json").writeText("{ broken descriptor")
        assertTrue(s.current() is ActiveStoreRead.Corrupt)
    }

    @Test
    fun `激活文件缺失时 current 返回 Absent`() {
        val s = store()
        val bytes = TestSuiteFixtures.validSuiteRaw.toByteArray()
        s.activate(bytes, descriptor(bytes))
        File(tempDir, "active/agent-regression.json").delete()
        assertTrue(s.current() is ActiveStoreRead.Absent)
    }
}
