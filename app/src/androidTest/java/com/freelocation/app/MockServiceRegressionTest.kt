package com.freelocation.app

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 设备上的回归测试：在模拟器或真机上验证模拟定位服务的端到端行为。
 *
 * 运行前需要授予定位权限和模拟位置权限（scripts/regression.sh 会自动做）：
 *   adb shell pm grant com.freelocation.app android.permission.ACCESS_FINE_LOCATION
 *   adb shell appops set com.freelocation.app android:mock_location allow
 *
 * 只能证明标准安卓上的行为没有退化；HyperOS 的后台策略、高德自己的网络定位测不出来。
 */
@RunWith(AndroidJUnit4::class)
class MockServiceRegressionTest {

    private lateinit var ctx: Context
    private lateinit var lm: LocationManager

    // 测试用的目标点（WGS84，上海外滩附近）
    private val targetLat = 31.2397
    private val targetLng = 121.4998

    @Before
    fun setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        stopAndWait()
    }

    @After
    fun tearDown() = stopAndWait()

    private fun stopAndWait() {
        if (MockLocationService.lastStatus.running) {
            MockLocationService.stop(ctx)
            waitUntil(5_000) { !MockLocationService.lastStatus.running }
        }
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            if (cond()) return true
            SystemClock.sleep(100)
        }
        return cond()
    }

    private fun startAndWait() {
        MockLocationService.startFixed(ctx, targetLat, targetLng)
        assertTrue(
            "模拟服务没有进入运行状态，错误=${MockLocationService.lastStatus.error}",
            waitUntil(10_000) { MockLocationService.lastStatus.running },
        )
    }

    private fun Location.mockCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider

    /** 开始模拟后，系统 gps / network 的最后位置就是目标点，并且带模拟标记。 */
    @Test
    fun providersReportTargetAfterStart() {
        startAndWait()
        SystemClock.sleep(1_500)
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val loc = lm.getLastKnownLocation(p)
            requireNotNull(loc) { "$p 没有位置" }
            assertTrue("$p 不是模拟位置", loc.mockCompat())
            val d = GeoMath.distance(GeoMath.Point(targetLat, targetLng), GeoMath.Point(loc.latitude, loc.longitude))
            assertTrue("$p 距目标 ${d}m", d < 5)
        }
        val status = MockLocationService.lastStatus
        assertTrue("应接管 gps，实际 ${status.providers}", LocationManager.GPS_PROVIDER in status.providers)
        assertTrue("应接管 network，实际 ${status.providers}", LocationManager.NETWORK_PROVIDER in status.providers)
        // 1.4.0 起不接管 fused
        assertFalse("不应接管 fused，实际 ${status.providers}", LocationManager.FUSED_PROVIDER in status.providers)
    }

    /**
     * 模仿地图类 App：同时订阅 fused / gps / network，收到的位置必须全部是模拟坐标。
     * （1.4.0 用临时 probe 应用手动验证过，这里固化成自动化用例）
     */
    @Test
    fun subscriberReceivesOnlyMockedLocations() {
        startAndWait()
        val received = CopyOnWriteArrayList<Location>()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                received += location
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        val thread = HandlerThread("probe").apply { start() }
        try {
            // 订阅前让推送稳定一会，避免拿到订阅瞬间系统缓存里的旧位置
            SystemClock.sleep(1_000)
            for (p in listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                try {
                    lm.requestLocationUpdates(p, 500L, 0f, listener, thread.looper)
                } catch (_: IllegalArgumentException) {
                    // 某些设备没有 fused，跳过
                }
            }
            SystemClock.sleep(5_000)
        } finally {
            lm.removeUpdates(listener)
            thread.quitSafely()
        }

        assertTrue("5 秒内没收到任何位置", received.size >= 5)
        val bad = received.filter {
            !it.mockCompat() ||
                GeoMath.distance(GeoMath.Point(targetLat, targetLng), GeoMath.Point(it.latitude, it.longitude)) > 5
        }
        assertEquals(
            "收到 ${received.size} 条，其中非模拟或偏离目标的：" +
                bad.joinToString { "${it.provider}/${it.latitude},${it.longitude}/mock=${it.mockCompat()}" },
            0, bad.size,
        )
    }

    /** 停止后测试来源被移除，服务状态变为未运行。 */
    @Test
    fun stopRemovesTestProviders() {
        startAndWait()
        MockLocationService.stop(ctx)
        assertTrue("停止后服务仍在运行", waitUntil(5_000) { !MockLocationService.lastStatus.running })
        SystemClock.sleep(500)
        // 测试来源被移除后，再往里推位置会抛 IllegalArgumentException
        var removed = false
        try {
            lm.setTestProviderLocation(
                LocationManager.GPS_PROVIDER,
                Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 0.0; longitude = 0.0; accuracy = 1f
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                },
            )
        } catch (_: IllegalArgumentException) {
            removed = true
        } catch (_: SecurityException) {
            removed = true
        }
        assertTrue("停止后 gps 测试来源仍然存在", removed)
    }

    /** 连续开始/停止几轮，服务不崩、状态不乱（覆盖 1.2.0 的启动即闪退）。 */
    @Test
    fun repeatedStartStopIsStable() {
        repeat(4) { round ->
            startAndWait()
            SystemClock.sleep(700)
            assertTrue("第 ${round + 1} 轮运行中状态丢失", MockLocationService.lastStatus.running)
            MockLocationService.stop(ctx)
            assertTrue("第 ${round + 1} 轮停不下来", waitUntil(5_000) { !MockLocationService.lastStatus.running })
        }
    }

    /** 诊断导出在服务运行时可用，且包含推送计数。 */
    @Test
    fun diagnosticsAvailableWhileRunning() {
        startAndWait()
        SystemClock.sleep(1_200)
        val d = MockLocationService.diagnostics()
        assertTrue(d, d.contains("FakeGps 诊断"))
        assertTrue(d, d.contains("开始注入"))
        val ok = Regex("""推送成功 gps=(\d+)""").find(d)?.groupValues?.get(1)?.toLong() ?: 0
        assertTrue("推送次数=$ok", ok > 10)
    }
}
