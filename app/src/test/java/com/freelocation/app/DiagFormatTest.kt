package com.freelocation.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归：1.2.0 点「开始模拟」闪退，原因是日志时间格式串错误。
 * 这里的每个格式化函数都至少调用一次，保证不会再抛格式化异常。
 */
class DiagFormatTest {

    @Test
    fun timestampHasMillisAndTrailingSpace() {
        val t = DiagFormat.timestamp(0L)
        assertTrue("got '$t'", Regex("""\d{2}:\d{2}:\d{2}\.\d{3} """).matches(t))
    }

    @Test
    fun fullTimeFormat() {
        val t = DiagFormat.fullTime(System.currentTimeMillis())
        assertTrue("got '$t'", Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(t))
    }

    @Test
    fun readbackEntryFormat() {
        val s = DiagFormat.readbackEntry("gps", true, true, 0.4, 2f, 15)
        assertEquals("gps:enabled=true mock=true 距目标=0m acc=2m age=15ms ", s)
    }

    @Test
    fun deliverySampleHandlesNullProvider() {
        assertEquals("gps/mock=true/84m", DiagFormat.deliverySample("gps", true, 84.2))
        assertEquals("null/mock=false/0m", DiagFormat.deliverySample(null, false, 0.0))
    }

    @Test
    fun notificationTextUsesDotDecimalRegardlessOfLocale() {
        val old = java.util.Locale.getDefault()
        try {
            // 部分语言环境的小数点是逗号，通知里的坐标不能因此变样
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("定点  31.239700, 121.499800", DiagFormat.notificationText(false, 31.2397, 121.4998))
            assertEquals("路线  -33.860000, 151.210000", DiagFormat.notificationText(true, -33.86, 151.21))
        } finally {
            java.util.Locale.setDefault(old)
        }
    }
}
