package top.qxwkstudio.travel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncRulesTest {

    @Test
    fun `new or modified records need push`() {
        assertTrue(SyncRules.shouldPush(modifiedAt = 200, syncedAt = 100))
        assertFalse(SyncRules.shouldPush(modifiedAt = 100, syncedAt = 100))
        assertFalse(SyncRules.shouldPush(modifiedAt = 50, syncedAt = 100))
    }

    @Test
    fun `tombstone uses deletion modified time`() {
        assertTrue(SyncRules.shouldPush(modifiedAt = 300, syncedAt = 200))
    }

    @Test
    fun `remote older than local must not overwrite local`() {
        assertFalse(SyncRules.shouldApplyRemote(localModified = 200, remoteModified = 100))
        assertTrue(SyncRules.shouldApplyRemote(localModified = 100, remoteModified = 200))
        assertTrue(SyncRules.shouldApplyRemote(localModified = 200, remoteModified = 100, force = true))
    }

    @Test
    fun `higher remote revision decides existence`() {
        // 远端 revision 更大：删除/复活以远端为准，时间戳不参与
        assertTrue(SyncRules.remoteWinsExistence(localRevision = 2, remoteRevision = 3))
        assertTrue(SyncRules.remoteWinsExistence(localRevision = 2, remoteRevision = 3, force = true))
        // revision 相同或更小：存在性不由远端决定（交给时间/冲突判定）
        assertFalse(SyncRules.remoteWinsExistence(localRevision = 3, remoteRevision = 3))
        assertFalse(SyncRules.remoteWinsExistence(localRevision = 3, remoteRevision = 2))
    }

    @Test
    fun `cursor only moves forward`() {
        assertEquals(7, SyncRules.nextCursor(3, 7))
        assertEquals(7, SyncRules.nextCursor(7, 3))
    }

    @Test
    fun `only conflict status is conflict`() {
        assertTrue(SyncRules.isConflict("conflict"))
        assertFalse(SyncRules.isConflict("applied"))
        assertFalse(SyncRules.isConflict("ignored"))
    }
}
