package com.freelocation.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断日志和通知里用到的文本格式化。
 *
 * 单独抽出来是为了能在 JVM 单元测试里覆盖：1.2.0 的闪退就是这里的格式串写错
 * （"%tH:%TM:%TS" 缺参数），在服务线程里抛 MissingFormatArgumentException 导致整个 App 崩溃。
 */
object DiagFormat {

    /** 日志行前缀时间，如 "15:04:52.597 "。 */
    fun timestamp(millis: Long): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(millis)) + " "

    /** 诊断报告头部的完整时间。 */
    fun fullTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    /** 单个定位来源的回读结果。 */
    fun readbackEntry(
        provider: String,
        enabled: Boolean,
        mock: Boolean,
        distanceMeters: Double,
        accuracyMeters: Float,
        ageMs: Long,
    ): String = String.format(
        Locale.US,
        "%s:enabled=%s mock=%s 距目标=%.0fm acc=%.0fm age=%dms ",
        provider, enabled, mock, distanceMeters, accuracyMeters, ageMs,
    )

    /** 系统投递样本，如 "gps/mock=true/0m"。 */
    fun deliverySample(provider: String?, mock: Boolean, distanceMeters: Double): String =
        String.format(Locale.US, "%s/mock=%s/%.0fm", provider, mock, distanceMeters)

    /** 通知栏文字。 */
    fun notificationText(route: Boolean, lat: Double, lng: Double): String =
        String.format(Locale.US, "%s  %.6f, %.6f", if (route) "路线" else "定点", lat, lng)
}
