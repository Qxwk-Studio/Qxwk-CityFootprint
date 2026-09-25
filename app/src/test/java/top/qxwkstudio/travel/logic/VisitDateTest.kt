package top.qxwkstudio.travel.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * visit_date 的校验与展示。
 *
 * 这里的期望值全部来自后端 worker.js 与网页版的实际行为，**不是「我觉得应该这样」**：
 * 客户端一旦比后端严，就会出现「本地拦下、后端其实也认」这种最难查的分叉。
 */
class VisitDateTest {

    // ── isValid：与后端 ^\d{4}(-\d{2})?$ 完全一致 ──

    @Test
    fun `空值与空白都算不填`() {
        assertTrue(VisitDate.isValid(null))
        assertTrue(VisitDate.isValid(""))
        assertTrue(VisitDate.isValid("   "))
    }

    @Test
    fun `接受 年 与 年月 两种形态`() {
        assertTrue(VisitDate.isValid("2024"))
        assertTrue(VisitDate.isValid("2024-08"))
        assertTrue(VisitDate.isValid(" 2024-08 "))
    }

    @Test
    fun `不补零 不接受 2024-8`() {
        assertFalse(VisitDate.isValid("2024-8"))
    }

    @Test
    fun `月份范围不管 与后端保持一致`() {
        // 后端只做形态校验，"2024-99" 是收的。这里刻意不擅自收紧（见 VisitDate 文件头的说明）
        assertTrue(VisitDate.isValid("2024-99"))
    }

    @Test
    fun `其它形态一律拒绝`() {
        assertFalse(VisitDate.isValid("24"))
        assertFalse(VisitDate.isValid("2024-08-01"))
        assertFalse(VisitDate.isValid("2024/08"))
        assertFalse(VisitDate.isValid("去年"))
        assertFalse(VisitDate.isValid("20248"))
    }

    // ── toWire：空白发 null（不要发空串）──

    @Test
    fun `空白转成 null 其余去掉首尾空格`() {
        assertNull(VisitDate.toWire(null))
        assertNull(VisitDate.toWire(""))
        assertNull(VisitDate.toWire("  "))
        assertEquals("2024", VisitDate.toWire(" 2024 "))
        assertEquals("2024-08", VisitDate.toWire("2024-08"))
    }

    // ── display：存储形态 → 展示形态 ──

    @Test
    fun `未填写时显示未填写而不是空白`() {
        assertEquals("未填写", VisitDate.display(null))
        assertEquals("未填写", VisitDate.display(""))
        assertEquals("未填写", VisitDate.display("  "))
    }

    @Test
    fun `年份与年月分别显示成中文`() {
        assertEquals("2024年", VisitDate.display("2024"))
        assertEquals("2024年8月", VisitDate.display("2024-08"))
    }

    @Test
    fun `异常输入原样显示 不去猜`() {
        // display 只服务界面：库里万一有脏数据（手工写库/旧数据），
        // 显示原文比抛异常或显示「未填写」都更容易排查
        assertEquals("2024-xx", VisitDate.display("2024-xx"))
    }
}