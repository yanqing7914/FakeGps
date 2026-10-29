package com.freelocation.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * 捕获未处理异常，写到应用私有目录，下次启动时提示用户导出。
 * 没有 adb 的时候，这是唯一能拿到崩溃堆栈的办法。
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"

    private fun file(ctx: Context) = File(ctx.filesDir, FILE_NAME)

    /** 安装全局异常处理器。 */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                save(app, thread, throwable)
            } catch (_: Throwable) {
                // 写日志本身失败时不能影响原有处理
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun save(ctx: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { throwable.printStackTrace(it) }
        val text = buildString {
            appendLine("崩溃时间: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            appendLine("线程: ${thread.name}")
            appendLine("机型: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})")
            appendLine()
            appendLine(sw.toString())
        }
        file(ctx).writeText(text)
    }

    /** 读取并清除上次的崩溃记录。 */
    fun take(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        val text = try { f.readText() } catch (_: Exception) { null }
        f.delete()
        return text
    }
}
