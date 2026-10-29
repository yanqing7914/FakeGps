package com.freelocation.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * 前台服务：持续向系统推送模拟坐标（WGS84）。
 *
 * 三种运动方式：
 * - 定点：停在一个位置
 * - 路线：沿路线按设定速度往返移动
 * - 摇杆：在当前位置基础上，按悬浮摇杆的方向和速度移动
 */
class MockLocationService : Service() {

    enum class Mode { FIXED, ROUTE }

    /** 推送给界面的状态。 */
    data class Status(
        val running: Boolean,
        val mode: Mode = Mode.FIXED,
        val position: GeoMath.Point? = null,
        val error: String? = null,
        /** 已接管的定位来源，如 gps / network / fused。 */
        val providers: List<String> = emptyList(),
    )

    companion object {
        private const val TAG = "MockLocationService"
        private const val CHANNEL_ID = "mock_location"
        private const val NOTIFICATION_ID = 1
        /**
         * 推送间隔 10ms。间隔越短，系统里的模拟位置越新，
         * 其他来源的位置插进来后也会很快被覆盖。代价是耗电更高。
         */
        private const val PUSH_INTERVAL_MS = 10L

        const val ACTION_START_FIXED = "start_fixed"
        const val ACTION_START_ROUTE = "start_route"
        const val ACTION_STOP = "stop"
        const val ACTION_TOGGLE_JOYSTICK = "toggle_joystick"

        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_ROUTE = "route"   // DoubleArray: lat0,lng0,lat1,lng1...
        const val EXTRA_SPEED = "speed"   // 米/秒
        const val EXTRA_LOOP = "loop"

        /** 界面订阅状态（主线程回调）。 */
        @Volatile
        var statusListener: ((Status) -> Unit)? = null

        @Volatile
        var lastStatus: Status = Status(running = false)
            private set

        fun startFixed(ctx: Context, wgsLat: Double, wgsLng: Double) {
            val i = Intent(ctx, MockLocationService::class.java)
                .setAction(ACTION_START_FIXED)
                .putExtra(EXTRA_LAT, wgsLat)
                .putExtra(EXTRA_LNG, wgsLng)
            ctx.startForegroundService(i)
        }

        fun startRoute(ctx: Context, wgsRoute: List<GeoMath.Point>, speed: Double, loop: Boolean) {
            val arr = DoubleArray(wgsRoute.size * 2)
            wgsRoute.forEachIndexed { i, p -> arr[i * 2] = p.lat; arr[i * 2 + 1] = p.lng }
            val i = Intent(ctx, MockLocationService::class.java)
                .setAction(ACTION_START_ROUTE)
                .putExtra(EXTRA_ROUTE, arr)
                .putExtra(EXTRA_SPEED, speed)
                .putExtra(EXTRA_LOOP, loop)
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, MockLocationService::class.java).setAction(ACTION_STOP))
        }

        fun toggleJoystick(ctx: Context) {
            ctx.startService(Intent(ctx, MockLocationService::class.java).setAction(ACTION_TOGGLE_JOYSTICK))
        }

        /** 推送成功/失败计数，以及系统里各个来源最后一次定位的回读结果。 */
        private val diagLock = Any()
        private val diagLines = ArrayDeque<String>()
        private var okGps = 0L
        private var okNetwork = 0L
        private var okFused = 0L
        private var failCount = 0L
        private var lastError: String? = null
        private var lastReadback = ""
        private var lastReadbackMs = 0L
        private var lastReassertMs = 0L
        private var stalledCount = 0L
        private var stalledTotalMs = 0L
        private var lastStallMs = 0L
        private var deliveredCount = 0L
        private var deliveredMock = 0L
        private var deliveredReal = 0L
        private val deliveredSamples = ArrayDeque<String>()

        /** 日志文件，进程被杀也保留。 */
        @Volatile private var logFile: java.io.File? = null

        /** 记录进程启动，用来判断 App 是否被系统杀掉重开。 */
        fun markProcessStart() {
            diag("进程启动 pid=${android.os.Process.myPid()} 模拟运行中=${lastStatus.running}")
        }

        fun attachLogFile(ctx: Context) {
            if (logFile == null) logFile = java.io.File(ctx.filesDir, "diag.log")
        }

        private fun appendToFile(line: String) {
            val f = logFile ?: return
            try {
                if (f.length() > 512_000) f.writeText("")   // 太大就重来
                f.appendText(line + "\n")
            } catch (_: Exception) {}
        }

        /** 影响高德/百度自研定位的信号：Wi‑Fi、WLAN 扫描、蓝牙扫描、移动数据。 */
        private fun scanState(): String {
            val ctx = ServiceHolder.app ?: return "未知"
            val wifi = ctx.getSystemService(android.net.wifi.WifiManager::class.java)
            val wifiOn = wifi?.isWifiEnabled == true
            val scanAlways = try { wifi?.isScanAlwaysAvailable == true } catch (_: Exception) { false }
            val bleScan = try {
                android.provider.Settings.Global.getInt(ctx.contentResolver, "ble_scan_always_enabled", 0) == 1
            } catch (_: Exception) { false }
            val wifiScanSetting = try {
                android.provider.Settings.Global.getInt(ctx.contentResolver, "wifi_scan_always_enabled", 0) == 1
            } catch (_: Exception) { false }
            val mobileData = try {
                android.provider.Settings.Global.getInt(ctx.contentResolver, "mobile_data", 1) == 1
            } catch (_: Exception) { false }
            val airMode = try {
                android.provider.Settings.Global.getInt(ctx.contentResolver, "airplane_mode_on", 0) == 1
            } catch (_: Exception) { false }
            return "wifi=$wifiOn WLAN扫描=$scanAlways wifi扫描设置=$wifiScanSetting 蓝牙扫描=$bleScan 移动数据=$mobileData 飞行模式=$airMode"
        }

        /** 由服务实例设置，返回当前已注册的来源。 */
        @Volatile
        var providerSnapshotter: (() -> String)? = null

        fun providersSnapshot(): String = providerSnapshotter?.invoke() ?: ""

        fun lastReadbackText(): String = synchronized(diagLock) { lastReadback }

        /** 诊断信息，用于导出给开发者排查。 */
        fun diagnostics(): String = synchronized(diagLock) {
            buildString {
                appendLine("FakeGps 诊断")
                appendLine("时间: " + DiagFormat.fullTime(System.currentTimeMillis()))
                appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("注册的来源: " + providersSnapshot())
                appendLine("推送成功 gps=$okGps network=$okNetwork fused=$okFused 失败=$failCount")
                appendLine("最近错误: ${lastError ?: "无"}")
                appendLine("网络定位干扰: " + scanState())
                appendLine("系统投递: 共=$deliveredCount 其中伪造=$deliveredMock 真实=$deliveredReal")
                appendLine("投递样本: " + deliveredSamples.joinToString(" | "))
                appendLine("推送中断: 次数=$stalledCount 累计=${stalledTotalMs}ms 最近=${lastStallMs}ms")
                appendLine("最近回读: $lastReadback")
                appendLine("---- 记录 ----")
                diagLines.forEach { appendLine(it) }
            }
        }

        private fun diag(line: String) = synchronized(diagLock) {
            val t = DiagFormat.timestamp(System.currentTimeMillis())
            diagLines.addLast(t + line)
            Log.d(TAG, "DIAG $t$line")
            appendToFile(t + line)
            while (diagLines.size > 300) diagLines.removeFirst()
        }

    }

    private lateinit var locationManager: LocationManager
    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private val addedProviders = mutableListOf<String>()

    init {
        // 让诊断信息能拿到当前注册的来源列表
        providerSnapshotter = { addedProviders.toList().joinToString(",") }
    }
    private var joystick: JoystickOverlay? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // 以下状态只在 worker 线程读写
    private var mode = Mode.FIXED
    private var position = GeoMath.Point(0.0, 0.0)
    private var bearing = 0.0
    private var speed = 0.0
    private var route: List<GeoMath.Point> = emptyList()
    private var routeSpeed = 1.4
    private var routeLoop = true
    private var routeTraveled = 0.0
    private var routeForward = true
    private var lastTickMs = 0L
    private var lastUiMs = 0L
    private var ticking = false

    // 摇杆输入（主线程写，worker 读）
    @Volatile private var joyBearing = 0.0
    @Volatile private var joyMagnitude = 0.0
    @Volatile private var joySpeed = JoystickOverlay.SpeedMode.WALK.metersPerSecond

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        worker = HandlerThread("mock-location").apply { start() }
        handler = Handler(worker.looper)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FIXED -> {
                if (!enterForeground()) return START_NOT_STICKY
                val lat = intent.getDoubleExtra(EXTRA_LAT, 0.0)
                val lng = intent.getDoubleExtra(EXTRA_LNG, 0.0)
                handler.post {
                    mode = Mode.FIXED
                    position = GeoMath.Point(lat, lng)
                    speed = 0.0
                    beginTicking()
                }
            }
            ACTION_START_ROUTE -> {
                if (!enterForeground()) return START_NOT_STICKY
                val arr = intent.getDoubleArrayExtra(EXTRA_ROUTE) ?: DoubleArray(0)
                val pts = (0 until arr.size / 2).map { GeoMath.Point(arr[it * 2], arr[it * 2 + 1]) }
                val spd = intent.getDoubleExtra(EXTRA_SPEED, 1.4)
                val loop = intent.getBooleanExtra(EXTRA_LOOP, true)
                if (pts.size < 2) {
                    publish(Status(running = false, error = "路线至少需要 2 个点"))
                    stopSelfSafely()
                } else handler.post {
                    mode = Mode.ROUTE
                    route = pts
                    routeSpeed = spd
                    routeLoop = loop
                    routeTraveled = 0.0
                    routeForward = true
                    position = pts.first()
                    beginTicking()
                }
            }
            ACTION_TOGGLE_JOYSTICK -> toggleJoystick()
            ACTION_STOP -> stopSelfSafely()
            null -> stopSelfSafely() // 被系统重启但没有任务，直接退出
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        handler.post { removeProviders() }
        releaseWakeLock()
        stopListeningDelivered()
        worker.quitSafely()
        mainHandler.post { joystick?.dismiss(); joystick = null }
        publish(Status(running = false))
        super.onDestroy()
    }

    // ---------------- 前台通知 ----------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "模拟定位运行状态", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MockLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val joy = PendingIntent.getService(
            this, 2, Intent(this, MockLocationService::class.java).setAction(ACTION_TOGGLE_JOYSTICK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("模拟定位运行中")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "摇杆", joy).build())
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    /** @return 是否成功进入前台。失败时上报原因而不是让进程崩溃。 */
    private fun enterForeground(): Boolean {
        val n = buildNotification("正在启动…")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            true
        } catch (e: Exception) {
            // Android 14 起，没有定位权限时以 location 类型启动前台服务会抛 SecurityException
            Log.e(TAG, "startForeground failed", e)
            publish(
                Status(
                    running = false,
                    error = "无法启动模拟服务：${e.javaClass.simpleName}: ${e.message}\n\n" +
                        "常见原因是没给定位权限，或系统不允许后台定位。请到 设置 → 应用 → FakeGps → 权限 里允许定位。",
                ),
            )
            stopSelfSafely()
            false
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ---------------- 模拟位置提供者 ----------------

    /** 最近一次注册失败的原因，显示给用户。 */
    private var providerError: String? = null

    /**
     * 注册测试提供者。
     *
     * 只有 GPS 是必须的；network / fused 在部分 ROM（如 HyperOS）上可能被拒绝，
     * 失败时跳过，不影响 GPS 模拟。
     */
    private fun ensureProviders(): Boolean {
        if (LocationManager.GPS_PROVIDER in addedProviders) return true
        providerError = null
        addProvider(LocationManager.GPS_PROVIDER, network = false, satellite = true, fine = true, required = true)
        if (LocationManager.GPS_PROVIDER !in addedProviders) return false
        addProvider(LocationManager.NETWORK_PROVIDER, network = true, satellite = false, fine = false, required = false)
        // fused 是可选接管：高德等 App 常请求 fused，接管它可以覆盖它们；但部分 ROM 上会冲突
        // 只接管 gps + network，不接管 fused。接管 fused 会替换掉系统的融合定位服务，
        // 部分 ROM 上会和系统定位冲突。回归测试里有用例保证这一点。
        if (false) {
            addProvider(LocationManager.FUSED_PROVIDER, network = false, satellite = false, fine = true, required = false)
        }
        Log.i(TAG, "mock providers: $addedProviders")
        return true
    }

    private fun addProvider(name: String, network: Boolean, satellite: Boolean, fine: Boolean, required: Boolean) {
        try {
            try { locationManager.removeTestProvider(name) } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.addTestProvider(
                    name,
                    ProviderProperties.Builder()
                        .setHasNetworkRequirement(network)
                        .setHasSatelliteRequirement(satellite)
                        .setHasAltitudeSupport(true)
                        .setHasSpeedSupport(true)
                        .setHasBearingSupport(true)
                        // 两个来源都声明低功耗 + 高精度
                        .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                        .setAccuracy(ProviderProperties.ACCURACY_FINE)
                        .build(),
                )
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    name, network, satellite, false, false, true, true, true,
                    Criteria.POWER_LOW,
                    Criteria.ACCURACY_FINE,
                )
            }
            locationManager.setTestProviderEnabled(name, true)
            if (name !in addedProviders) addedProviders += name
        } catch (e: SecurityException) {
            Log.w(TAG, "add provider $name denied", e)
            if (required) providerError =
                "系统拒绝模拟定位：请到「开发者选项 → 选择模拟位置信息应用」选择「${getString(R.string.app_name)}」"
        } catch (e: Exception) {
            Log.w(TAG, "add provider $name failed", e)
            if (required) providerError = "注册 GPS 模拟失败：${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /**
     * 读回系统里各个来源的最近一次定位，看系统到底记的是我们的模拟坐标还是真实坐标。
     * 这是判断"注入是否生效"最直接的方式。
     */
    @SuppressLint("MissingPermission")
    private fun readback() {
        val target = position
        val sb = StringBuilder()
        for (name in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val enabled = try { locationManager.isProviderEnabled(name) } catch (_: Exception) { false }
            val loc = try { locationManager.getLastKnownLocation(name) } catch (e: Exception) { null }
            if (loc == null) {
                sb.append("$name:enabled=$enabled,无定位 ")
                continue
            }
            val mock = if (Build.VERSION.SDK_INT >= 31) loc.isMock else @Suppress("DEPRECATION") loc.isFromMockProvider
            val dist = GeoMath.distance(target, GeoMath.Point(loc.latitude, loc.longitude))
            sb.append(
                DiagFormat.readbackEntry(
                    name, enabled, mock, dist, loc.accuracy,
                    (android.os.SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000,
                ),
            )
        }
        lastReadback = sb.toString()
        lastReadbackMs = android.os.SystemClock.elapsedRealtime()
        diag("readback " + lastReadback)
    }

    /** 重新注册已接管的来源（先移除再添加），让它们始终是测试提供者。 */
    private fun reassertProviders() {
        val names = addedProviders.toList()
        for (name in names) {
            try {
                val fine = name != LocationManager.NETWORK_PROVIDER
                addProvider(
                    name,
                    network = name == LocationManager.NETWORK_PROVIDER,
                    satellite = name == LocationManager.GPS_PROVIDER,
                    fine = fine,
                    required = false,
                )
            } catch (e: Exception) {
                diag("重新注册 $name 失败: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun removeProviders() {
        for (name in addedProviders) {
            try {
                locationManager.removeTestProvider(name)
            } catch (e: Exception) {
                Log.w(TAG, "remove provider $name failed", e)
            }
        }
        addedProviders.clear()
    }

    /**
     * 推送一次模拟坐标。
     * 部分 ROM 在真实定位拿到结果后会悄悄把我们的测试提供者摘掉，
     * 这时推送会失败，需要重新注册。
     * @return 是否至少有一个来源推送成功
     */
    private fun pushLocation(p: GeoMath.Point, bearingDeg: Double, speedMps: Double): Boolean {
        val now = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtimeNanos()
        var ok = 0
        for (name in addedProviders.toList()) {
            val isGps = name == LocationManager.GPS_PROVIDER
            val loc = Location(name).apply {
                latitude = p.lat
                longitude = p.lng
                altitude = 55.0
                // 精度参考影梭（GoGoGo）：GPS 给 1 米，网络定位给 2 米。
                // 地图 SDK 融合多个来源时会偏向精度更高的结果，精度越高越不容易被真实位置覆盖。
                // 两个来源用同样的精度，避免高德融合时偏向另一个来源
                accuracy = 2.0f
                // 部分地图 App 会读取卫星数，没有时可能认为 GPS 信号无效
                extras = Bundle().apply { putInt("satellites", 7) }
                // 静止时用固定方向、不设速度；移动时设置方向和速度
                bearing = if (speedMps > 0) bearingDeg.toFloat() else 1.0f
                if (speedMps > 0) speed = speedMps.toFloat()
                time = now
                elapsedRealtimeNanos = elapsed
                // 不设置垂直/方向/速度精度字段
            }
            try {
                locationManager.setTestProviderLocation(name, loc)
                ok++
                when (name) {
                    LocationManager.GPS_PROVIDER -> okGps++
                    LocationManager.NETWORK_PROVIDER -> okNetwork++
                    else -> okFused++
                }
            } catch (e: Exception) {
                failCount++
                lastError = "$name: ${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "push $name failed", e)
            }
        }
        return ok > 0
    }

    // ---------------- 主循环 ----------------

    /** 持有 CPU 唤醒锁，保证熄屏后推送循环还在跑。 */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FakeGps:mock")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** 记录系统实际投递给应用的位置，判断真假定位是否混在一起。 */
    private val deliveryListener = object : android.location.LocationListener {
        override fun onLocationChanged(location: Location) {
            val mock = if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider
            synchronized(diagLock) {
                deliveredCount++
                if (mock) deliveredMock++ else deliveredReal++
                if (deliveredSamples.size >= 6) deliveredSamples.removeFirst()
                val target = position
                val dist = GeoMath.distance(target, GeoMath.Point(location.latitude, location.longitude))
                deliveredSamples.addLast(
                    DiagFormat.deliverySample(location.provider, mock, dist),
                )
                // 出现真实定位说明系统里混进了真数据，这是高德跳回真实位置的关键线索
                if (!mock) diag("收到真实定位! ${location.provider} ${location.latitude},${location.longitude} 距目标=${dist.toInt()}m")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun startListeningDelivered() {
        for (name in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.FUSED_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )) {
            try {
                locationManager.requestLocationUpdates(name, 0L, 0f, deliveryListener, Looper.getMainLooper())
                diag("已监听系统投递: $name")
            } catch (e: Exception) {
                diag("监听 $name 失败: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun stopListeningDelivered() {
        try { locationManager.removeUpdates(deliveryListener) } catch (_: Exception) {}
    }

    private fun beginTicking() {
        if (!ensureProviders()) {
            publish(Status(running = false, error = providerError ?: "注册 GPS 模拟失败"))
            stopSelfSafely()
            return
        }
        acquireWakeLock()
        diag("=== 服务启动 pid=${android.os.Process.myPid()} ===")
        // 不再监听系统投递：requestLocationUpdates 会触发系统去开真实定位硬件
        diag("开始注入 间隔=${PUSH_INTERVAL_MS}ms 来源=$addedProviders mockFused=${MockOptions.mockFused}")
        lastTickMs = SystemClock.elapsedRealtime()
        if (!ticking) {
            ticking = true
            handler.post(tick)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val gap = now - lastTickMs
            // 正常情况下间隔只有 10ms。出现明显间隔说明进程被系统冻结/挂起了
            if (gap > 1500) {
                synchronized(diagLock) {
                    stalledCount++
                    stalledTotalMs += gap
                    lastStallMs = gap
                }
                diag("推送中断 ${gap}ms（进程可能被系统冻结）")
            }
            val dt = (gap.coerceIn(0, 2000)) / 1000.0
            lastTickMs = now

            val mag = joyMagnitude
            if (mag > 0.05) {
                // 摇杆优先：路线模式下推动摇杆会切到自由移动
                if (mode == Mode.ROUTE) mode = Mode.FIXED
                speed = joySpeed * mag
                bearing = joyBearing
                position = GeoMath.move(position, bearing, speed * dt)
            } else if (mode == Mode.ROUTE) {
                stepRoute(dt)
            } else {
                speed = 0.0
            }

            val pushed = pushLocation(position, bearing, speed)
            if (!pushed) {
                // 提供者被系统摘掉了，重新注册后继续
                Log.w(TAG, "all providers lost, re-registering")
                addedProviders.clear()
                if (!ensureProviders()) {
                    publish(Status(running = false, error = providerError ?: "模拟定位被系统终止"))
                    stopSelfSafely()
                    return
                }
            }

            if (now - lastReadbackMs >= 5000) readback()

            // 定期重新注册，防止系统在真实定位到达后把我们的测试提供者悄悄换掉
            // 不再周期性重新注册：remove+add 的空档会放真实定位进来

            // 界面和通知每秒刷新一次即可，系统会限制通知的更新频率
            if (now - lastUiMs >= 1000) {
                lastUiMs = now
                publish(Status(running = true, mode = mode, position = position, providers = addedProviders.toList()))
                updateNotification(
                    DiagFormat.notificationText(mode == Mode.ROUTE, position.lat, position.lng),
                )
            }

            // 高频推送：真实 GPS 一般 1 秒 1 次，部分 App 会丢弃"过期"位置，推快一点更稳
            handler.postDelayed(this, PUSH_INTERVAL_MS)
        }
    }

    private fun stepRoute(dt: Double) {
        val total = GeoMath.routeLength(route)
        if (total <= 0) return
        routeTraveled += routeSpeed * dt
        if (routeTraveled >= total) {
            if (routeLoop) {
                // 往返：到终点后掉头
                routeTraveled -= total
                routeForward = !routeForward
            } else {
                routeTraveled = total
            }
        }
        val path = if (routeForward) route else route.asReversed()
        val (p, br) = GeoMath.positionAlong(path, routeTraveled)
        position = p
        bearing = br
        speed = if (!routeLoop && routeTraveled >= total) 0.0 else routeSpeed
    }

    // ---------------- 摇杆 ----------------

    private fun toggleJoystick() {
        mainHandler.post {
            val existing = joystick
            if (existing != null) {
                existing.dismiss()
                joystick = null
                joyMagnitude = 0.0
                return@post
            }
            if (!lastStatus.running) {
                publish(lastStatus.copy(error = "请先开始模拟，再打开摇杆"))
                return@post
            }
            if (!Settings.canDrawOverlays(this)) {
                publish(lastStatus.copy(error = "需要悬浮窗权限才能显示摇杆"))
                return@post
            }
            joystick = JoystickOverlay(
                this,
                onInput = { br, mag -> joyBearing = br; joyMagnitude = mag },
                onSpeedChange = { joySpeed = it.metersPerSecond },
                onClose = { joystick = null; joyMagnitude = 0.0 },
            ).also { it.show() }
        }
    }

    // ---------------- 工具 ----------------

    private fun publish(s: Status) {
        lastStatus = s
        mainHandler.post { statusListener?.invoke(s) }
    }

    private fun stopSelfSafely() {
        ticking = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
