package com.freelocation.app

import android.content.Context

/**
 * 运行时可调的实验开关，改完立即生效（每次开始模拟时读取）。
 * 用于排查不同 ROM 上哪种组合能生效。
 */
object MockOptions {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("options", Context.MODE_PRIVATE)

    /** 是否同时接管 fused 这个融合定位来源。 */
    fun isMockFused(ctx: Context) = prefs(ctx).getBoolean("mock_fused", true)

    fun setMockFused(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("mock_fused", on).apply()

    /** 从服务里读。 */
    val mockFused: Boolean
        get() {
            val ctx = ServiceHolder.app ?: return true
            return isMockFused(ctx)
        }
}

/** 保存 Application Context，供 [MockOptions] 在没有 Context 时读取。 */
object ServiceHolder {
    var app: Context? = null
}
