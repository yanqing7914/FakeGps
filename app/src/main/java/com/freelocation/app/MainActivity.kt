package com.freelocation.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * 主界面：地图选点 / 坐标输入 / 路线编辑 / 收藏夹 / 启动停止。
 *
 * 坐标约定：界面和地图使用 GCJ-02（高德瓦片），只在交给 [MockLocationService] 前转成 WGS84。
 */
class MainActivity : Activity() {

    private lateinit var map: WebView
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var joystickButton: Button
    private lateinit var coordInput: EditText
    private lateinit var coordType: Spinner
    private lateinit var fixedPanel: LinearLayout
    private lateinit var routePanel: LinearLayout
    private lateinit var routeInfo: TextView
    private lateinit var speedSpinner: Spinner
    private lateinit var loopCheck: CheckBox
    private lateinit var setupBanner: LinearLayout
    private lateinit var setupText: TextView
    private lateinit var setupButton: Button

    private lateinit var favorites: Favorites
    private lateinit var fusedCheck: CheckBox
    private lateinit var readbackText: TextView
    private lateinit var searchInput: EditText
    private lateinit var locationManager: LocationManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 手机真实位置（WGS84），用于打开时定位和搜索结果排序。 */
    private var myLocation: Location? = null
    private var locating = false

    /** 选中的点（GCJ-02）。 */
    private var selected: Pair<Double, Double>? = null
    /** 路线点（GCJ-02）。 */
    private val routePoints = mutableListOf<Pair<Double, Double>>()
    private var routeMode = false
    private var mapReady = false

    private enum class CoordSystem(val label: String) { GCJ02("高德/腾讯 GCJ-02"), BD09("百度 BD-09"), WGS84("GPS WGS84") }

    private data class SpeedOption(val label: String, val mps: Double)
    private val speeds = listOf(
        SpeedOption("步行 5km/h", 1.4),
        SpeedOption("跑步 12km/h", 3.4),
        SpeedOption("骑行 20km/h", 5.6),
        SpeedOption("开车 40km/h", 11.1),
        SpeedOption("开车 80km/h", 22.2),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        favorites = Favorites(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        searchInput = findViewById(R.id.searchInput)
        fusedCheck = findViewById(R.id.fusedCheck)
        readbackText = findViewById(R.id.readback)
        fusedCheck.isChecked = MockOptions.isMockFused(this)
        fusedCheck.setOnCheckedChangeListener { _, on -> MockOptions.setMockFused(this, on) }

        map = findViewById(R.id.map)
        statusText = findViewById(R.id.status)
        startButton = findViewById(R.id.startButton)
        joystickButton = findViewById(R.id.joystickButton)
        coordInput = findViewById(R.id.coordInput)
        coordType = findViewById(R.id.coordType)
        fixedPanel = findViewById(R.id.fixedPanel)
        routePanel = findViewById(R.id.routePanel)
        routeInfo = findViewById(R.id.routeInfo)
        speedSpinner = findViewById(R.id.speedSpinner)
        loopCheck = findViewById(R.id.loopCheck)
        setupBanner = findViewById(R.id.setupBanner)
        setupText = findViewById(R.id.setupText)
        setupButton = findViewById(R.id.setupButton)

        coordType.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, CoordSystem.entries.map { it.label },
        )
        speedSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, speeds.map { it.label },
        )

        setupMap()

        findViewById<RadioGroup>(R.id.modeGroup).setOnCheckedChangeListener { _, id ->
            routeMode = id == R.id.modeRoute
            fixedPanel.visibility = if (routeMode) View.GONE else View.VISIBLE
            routePanel.visibility = if (routeMode) View.VISIBLE else View.GONE
            js("setRouteMode($routeMode)")
            if (!routeMode) selected?.let { (lat, lng) -> js("setSelected($lat,$lng,false)") }
        }
        findViewById<Button>(R.id.locateButton).setOnClickListener { locateFromInput() }
        findViewById<Button>(R.id.favButton).setOnClickListener { showFavorites() }
        findViewById<Button>(R.id.undoButton).setOnClickListener {
            if (routePoints.isNotEmpty()) {
                routePoints.removeAt(routePoints.size - 1)
                js("undoRoutePoint()")
                updateRouteInfo()
            }
        }
        findViewById<Button>(R.id.clearButton).setOnClickListener {
            routePoints.clear()
            js("clearRoute()")
            updateRouteInfo()
        }
        startButton.setOnClickListener { onStartStop() }
        joystickButton.setOnClickListener { onJoystick() }
        setupButton.setOnClickListener { openSetup() }
        findViewById<Button>(R.id.searchButton).setOnClickListener { doSearch() }
        findViewById<Button>(R.id.diagButton).setOnClickListener { shareDiagnostics() }
        findViewById<Button>(R.id.myLocationButton).setOnClickListener { locateMe(pan = true, userAction = true) }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false
        }

        requestRuntimePermissions()
        showLastCrash()
    }

    /** 上次崩溃了就提示导出堆栈。 */
    private fun showLastCrash() {
        val text = CrashLog.take(this) ?: return
        AlertDialog.Builder(this)
            .setTitle("上次运行崩溃了")
            .setPositiveButton("导出崩溃日志") { _, _ ->
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "FakeGps崩溃日志")
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                try {
                    startActivity(Intent.createChooser(i, "导出崩溃日志"))
                } catch (_: Exception) {
                    AlertDialog.Builder(this).setMessage(text).setPositiveButton("关闭", null).show()
                }
            }
            .setNeutralButton("查看") { _, _ ->
                AlertDialog.Builder(this).setTitle("崩溃日志").setMessage(text).setPositiveButton("关闭", null).show()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkSetup()
        if (hasLocationPermission()) locateMe(pan = true, userAction = false)
    }

    override fun onDestroy() {
        stopLocating()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        MockLocationService.statusListener = { render(it) }
        render(MockLocationService.lastStatus)
        checkSetup()
    }

    override fun onPause() {
        MockLocationService.statusListener = null
        super.onPause()
    }

    // ---------------- 地图 ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupMap() {
        map.settings.javaScriptEnabled = true
        map.settings.domStorageEnabled = true
        map.webViewClient = WebViewClient()
        map.addJavascriptInterface(Bridge(), "Android")
        map.loadUrl("file:///android_asset/map.html")
    }

    private fun js(code: String) {
        if (mapReady) map.evaluateJavascript(code, null)
    }

    /** 地图回调（在 WebView 线程调用，需要切回主线程）。 */
    inner class Bridge {
        @JavascriptInterface
        fun onMapReady() = runOnUiThread {
            mapReady = true
            selected?.let { js("setSelected(${it.first},${it.second},false)") }
            val running = MockLocationService.lastStatus.position
            if (running != null) {
                // 正在模拟：显示模拟位置
                showCurrent(running, follow = true)
            } else {
                // 没在模拟：定位到手机当前的真实位置
                locateMe(pan = true, userAction = false)
            }
        }

        @JavascriptInterface
        fun onMapClick(lat: Double, lng: Double) = runOnUiThread {
            selected = lat to lng
            coordType.setSelection(CoordSystem.GCJ02.ordinal)
            coordInput.setText(fmt(lat, lng))
        }

        @JavascriptInterface
        fun onRoutePointAdded(lat: Double, lng: Double) = runOnUiThread {
            routePoints += lat to lng
            updateRouteInfo()
        }
    }

    private fun showCurrent(wgs: GeoMath.Point, follow: Boolean) {
        val g = CoordConverter.wgs84ToGcj02(wgs.lat, wgs.lng)
        js("setCurrent(${g.lat},${g.lng},$follow)")
    }

    // ---------------- 我的位置 ----------------

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private var panOnFix = false

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            onRealLocation(loc)
            // 拿到精度足够的位置就停止，省电
            if (loc.hasAccuracy() && loc.accuracy <= 100f) stopLocating()
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /**
     * 获取手机当前的真实位置并显示在地图上。
     * 模拟运行中时系统返回的是模拟位置，这时不更新"我的位置"。
     */
    @SuppressLint("MissingPermission")
    private fun locateMe(pan: Boolean, userAction: Boolean) {
        if (!hasLocationPermission()) {
            if (userAction) requestRuntimePermissions()
            return
        }
        if (MockLocationService.lastStatus.running) {
            if (userAction) toast("模拟运行中，系统返回的是模拟位置。停止模拟后再定位。")
            return
        }
        panOnFix = pan

        // 先用缓存位置立即显示（取最新的一个）
        val cached = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { p -> try { locationManager.getLastKnownLocation(p) } catch (_: Exception) { null } }
            .filter { !it.isFromMockProviderCompat() }
            .maxByOrNull { it.time }
        if (cached != null) onRealLocation(cached)

        // 再请求一次新位置（华为等无谷歌服务的手机只有 gps / network）
        if (!locating) {
            val providers = locationManager.getProviders(true)
                .filter { it == LocationManager.GPS_PROVIDER || it == LocationManager.NETWORK_PROVIDER }
            if (providers.isEmpty()) {
                if (userAction) toast("手机定位服务没打开，请在下拉菜单里打开「位置信息」")
                return
            }
            locating = true
            for (p in providers) {
                try { locationManager.requestLocationUpdates(p, 1000L, 0f, locationListener, Looper.getMainLooper()) }
                catch (_: Exception) {}
            }
            // 最多等 20 秒
            mainHandler.postDelayed({ stopLocating() }, 20_000)
        }
        if (cached == null && userAction) toast("正在获取你的位置…")
    }

    private fun stopLocating() {
        if (!locating) return
        locating = false
        try { locationManager.removeUpdates(locationListener) } catch (_: Exception) {}
    }

    private fun onRealLocation(loc: Location) {
        if (MockLocationService.lastStatus.running || loc.isFromMockProviderCompat()) return
        val prev = myLocation
        if (prev != null && prev.time > loc.time && prev.accuracy <= loc.accuracy) return
        myLocation = loc
        val g = CoordConverter.wgs84ToGcj02(loc.latitude, loc.longitude)
        js("setMyLocation(${g.lat},${g.lng},$panOnFix)")
        if (panOnFix) {
            // 没选过点时，默认把"我的位置"作为选中点，方便直接在附近选
            if (selected == null) coordInput.setText(fmt(g.lat, g.lng))
            panOnFix = false
        }
    }

    @Suppress("DEPRECATION")
    private fun Location.isFromMockProviderCompat(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) isMock else isFromMockProvider

    // ---------------- 地点搜索 ----------------

    private fun doSearch() {
        val q = searchInput.text.toString().trim()
        if (q.isEmpty()) {
            toast("请输入要搜索的地点")
            return
        }
        // 输入的是坐标就直接定位
        parseCoord(q)?.let { (lat, lng) ->
            selected = lat to lng
            coordInput.setText(fmt(lat, lng))
            js("setSelected($lat,$lng,true)")
            hideKeyboard()
            return
        }
        hideKeyboard()
        statusText.text = "正在搜索「$q」…"
        val bias = myLocation
        Thread {
            val result = try {
                Result.success(PlaceSearch.search(q, bias?.latitude, bias?.longitude))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                render(MockLocationService.lastStatus)
                result.onSuccess { showSearchResults(q, it) }
                    .onFailure { toast("搜索失败，请检查网络后重试（${it.message}）") }
            }
        }.start()
    }

    private fun showSearchResults(q: String, items: List<PlaceSearch.Result>) {
        if (items.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("没有找到「$q」")
                .setMessage("可以换个说法，比如加上城市名：「杭州 西湖」。\n也可以在高德地图里长按地点复制坐标，粘贴到搜索框。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val labels = items.map { if (it.address.isEmpty()) it.name else "${it.name}\n${it.address}" }
        AlertDialog.Builder(this)
            .setTitle("搜索结果")
            .setItems(labels.toTypedArray()) { _, i ->
                val r = items[i]
                val g = CoordConverter.wgs84ToGcj02(r.wgsLat, r.wgsLng)
                if (routeMode) {
                    // 路线模式下：把搜索结果加为路线点
                    routePoints += g.lat to g.lng
                    js("addRoutePoint(${g.lat},${g.lng});map.setView([${g.lat},${g.lng}],16)")
                    updateRouteInfo()
                } else {
                    selected = g.lat to g.lng
                    coordType.setSelection(CoordSystem.GCJ02.ordinal)
                    coordInput.setText(fmt(g.lat, g.lng))
                    js("setSelected(${g.lat},${g.lng},true)")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(searchInput.windowToken, 0)
        searchInput.clearFocus()
    }

    // ---------------- 交互 ----------------

    private fun locateFromInput() {
        val parsed = parseCoord(coordInput.text.toString())
        if (parsed == null) {
            toast("格式不对，请输入：纬度,经度")
            return
        }
        val (lat, lng) = parsed
        val gcj = when (CoordSystem.entries[coordType.selectedItemPosition]) {
            CoordSystem.GCJ02 -> CoordConverter.LatLng(lat, lng)
            CoordSystem.BD09 -> CoordConverter.bd09ToGcj02(lat, lng)
            CoordSystem.WGS84 -> CoordConverter.wgs84ToGcj02(lat, lng)
        }
        selected = gcj.lat to gcj.lng
        js("setSelected(${gcj.lat},${gcj.lng},true)")
    }

    /** 用户选择"不再提示"后，本次运行期间不再弹网络定位提醒。 */
    private var skipNetworkWarning = false
    private var skipBatteryWarning = false

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm?.isIgnoringBatteryOptimizations(packageName) ?: true
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                toast("请手动到 设置 → 应用 → FakeGps → 省电策略 里选「无限制」")
            }
        }
    }

    /**
     * 系统的 Wi‑Fi / 基站网络定位是否可能干扰模拟。
     *
     * 高德等地图的定位 SDK 会自己扫描 Wi‑Fi 和基站，发到服务器算位置，这条路不经过
     * 系统的模拟定位接口。影梭（GoGoGo）FAQ 和多个项目的 issue 都提到：
     * 「会飘回真实位置，是因为手机还开启了基站定位、Wi‑Fi 定位」。
     */
    private fun networkLocationRisks(): List<String> {
        val risks = mutableListOf<String>()
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        if (wifi?.isWifiEnabled == true) risks += "WLAN（Wi‑Fi）开着"
        else if (wifi?.isScanAlwaysAvailable == true) risks += "「WLAN 扫描」开着（关了 Wi‑Fi 也会扫描）"
        try {
            if (Settings.Global.getInt(contentResolver, "ble_scan_always_enabled", 0) == 1) risks += "「蓝牙扫描」开着"
        } catch (_: Exception) {}
        return risks
    }

    private fun onStartStop() {
        if (MockLocationService.lastStatus.running) {
            MockLocationService.stop(this)
            return
        }
        if (!skipBatteryWarning && !isIgnoringBatteryOptimizations()) {
            AlertDialog.Builder(this)
                .setTitle("允许后台运行")
                .setMessage(
                    "小米/华为等系统会冻结后台应用。你打开高德时，如果「FakeGps」被冻结，" +
                        "推送就会停，位置就会跳回真实位置。\n\n" +
                        "1. 点下面的按钮，允许「FakeGps」不受电池优化限制\n" +
                        "2. 再到 设置 → 应用 → FakeGps → 省电策略，选「无限制」\n" +
                        "3. 同一个页面里打开「自启动」",
                )
                .setPositiveButton("去设置") { _, _ -> requestIgnoreBatteryOptimizations() }
                .setNeutralButton("仍然开始") { _, _ -> skipBatteryWarning = true; onStartStop() }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        val risks = if (skipNetworkWarning) emptyList() else networkLocationRisks()
        if (risks.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("高德等地图可能会跳回真实位置")
                .setMessage(
                    "检测到：" + risks.joinToString("、") + "\n\n" +
                        "高德、百度这类地图除了用 GPS，还会自己扫描周围的 Wi‑Fi 和基站来算位置，" +
                        "这部分模拟定位拦不住，所以会先显示模拟位置、几秒后跳回去。\n\n" +
                        "建议：\n" +
                        "1. 关闭 WLAN，改用流量\n" +
                        "2. 在「位置信息」设置里关闭「WLAN 扫描」「蓝牙扫描」\n" +
                        "3. 有「高精度定位 / 网络定位辅助」的也关掉\n" +
                        "4. 已经打开的高德先划掉后台，开始模拟后再重新打开",
                )
                .setPositiveButton("去关闭") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    }
                }
                .setNeutralButton("仍然开始") { _, _ ->
                    skipNetworkWarning = true
                    onStartStop()
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        // 不在这里拦截：部分 ROM 上 AppOps 检查不准，以服务实际注册结果为准。
        shownError = null
        if (routeMode) {
            if (routePoints.size < 2) {
                toast("路线至少需要 2 个点")
                return
            }
            val wgs = routePoints.map { (lat, lng) ->
                CoordConverter.gcj02ToWgs84(lat, lng).let { GeoMath.Point(it.lat, it.lng) }
            }
            val speed = speeds[speedSpinner.selectedItemPosition].mps
            if (!startServiceSafely { MockLocationService.startRoute(this, wgs, speed, loopCheck.isChecked) }) return
        } else {
            val sel = selected
            if (sel == null) {
                toast("请先在地图上点选位置，或输入坐标")
                return
            }
            favorites.lastPoint = sel
            val wgs = CoordConverter.gcj02ToWgs84(sel.first, sel.second)
            if (!startServiceSafely { MockLocationService.startFixed(this, wgs.lat, wgs.lng) }) return
        }
        statusText.text = "正在启动…"
    }

    /** 启动服务可能抛 SecurityException / ForegroundServiceStartNotAllowedException，不能让 App 崩掉。 */
    private inline fun startServiceSafely(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: Exception) {
        AlertDialog.Builder(this)
            .setTitle("启动失败")
            .setMessage("${e.javaClass.simpleName}: ${e.message}\n\n请检查是否已授予定位权限，以及「FakeGps」是否需要在应用设置里允许后台运行。")
            .setPositiveButton("知道了", null)
            .show()
        false
    }

    private fun onJoystick() {
        if (!MockLocationService.lastStatus.running) {
            toast("请先开始模拟，再打开摇杆")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("需要悬浮窗权限")
                .setMessage("摇杆以悬浮窗形式显示在其他应用上方。请在接下来的页面允许「显示悬浮窗」。")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    )
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        MockLocationService.toggleJoystick(this)
    }

    private fun showFavorites() {
        val items = favorites.list()
        val names = items.map { "${it.name}  (${fmt(it.lat, it.lng)})" }.toMutableList()
        val sel = selected
        val b = AlertDialog.Builder(this).setTitle("收藏夹")
        if (items.isEmpty()) b.setMessage("还没有收藏")
        else b.setItems(names.toTypedArray()) { _, i ->
            val p = items[i]
            selected = p.lat to p.lng
            coordType.setSelection(CoordSystem.GCJ02.ordinal)
            coordInput.setText(fmt(p.lat, p.lng))
            js("setSelected(${p.lat},${p.lng},true)")
        }
        if (sel != null) b.setPositiveButton("收藏当前点") { _, _ -> promptFavoriteName(sel) }
        if (items.isNotEmpty()) b.setNeutralButton("删除…") { _, _ -> promptDelete(items) }
        b.setNegativeButton("关闭", null).show()
    }

    private fun promptFavoriteName(p: Pair<Double, Double>) {
        val input = EditText(this).apply { hint = "名称，如 公司"; setSingleLine() }
        AlertDialog.Builder(this)
            .setTitle("收藏当前点")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { fmt(p.first, p.second) }
                favorites.add(Favorites.Place(name, p.first, p.second))
                toast("已收藏")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun promptDelete(items: List<Favorites.Place>) {
        AlertDialog.Builder(this)
            .setTitle("删除收藏")
            .setItems(items.map { it.name }.toTypedArray()) { _, i ->
                favorites.remove(items[i].name)
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateRouteInfo() {
        if (routePoints.isEmpty()) {
            routeInfo.text = "在地图上依次点击添加路线点"
            return
        }
        val len = GeoMath.routeLength(routePoints.map { GeoMath.Point(it.first, it.second) })
        routeInfo.text = String.format(
            Locale.CHINA, "已添加 %d 个点，全长 %s", routePoints.size,
            if (len >= 1000) String.format(Locale.CHINA, "%.2f 公里", len / 1000) else "${len.toInt()} 米",
        )
    }

    /** 已经弹过的错误，避免重复弹窗。 */
    private var shownError: String? = null

    private fun render(s: MockLocationService.Status) {
        if (s.error != null && s.error != shownError) {
            shownError = s.error
            AlertDialog.Builder(this)
                .setTitle("模拟没有启动")
                .setMessage(s.error)
                .setPositiveButton("知道了", null)
                .setNeutralButton("查看设置方法") { _, _ -> showSetupDialog() }
                .show()
        }
        if (s.running) {
            shownError = null
            startButton.text = "停止模拟"
            startButton.backgroundTintList = getColorStateList(R.color.danger)
            val pos = s.position
            val sources = if (s.providers.isEmpty()) "" else "\n已接管：${s.providers.joinToString(" / ")}"
            readbackText.text = MockLocationService.lastReadbackText()
            statusText.text = if (pos != null) {
                val g = CoordConverter.wgs84ToGcj02(pos.lat, pos.lng)
                "运行中（${if (s.mode == MockLocationService.Mode.ROUTE) "路线" else "定点"}） ${fmt(g.lat, g.lng)}$sources"
            } else "运行中"
            pos?.let { showCurrent(it, follow = s.mode == MockLocationService.Mode.ROUTE) }
        } else {
            startButton.text = "开始模拟"
            startButton.backgroundTintList = getColorStateList(R.color.primary)
            statusText.text = "未运行"
            js("clearCurrent()")
        }
    }

    // ---------------- 权限与设置 ----------------

    private fun requestRuntimePermissions() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    /** 是否已在开发者选项中被选为模拟位置应用。 */
    private fun isMockLocationApp(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        return try {
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
            } else {
                ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    private fun checkSetup() {
        val tips = mutableListOf<String>()
        if (!isMockLocationApp()) {
            tips += if (SetupGuide.isDeveloperModeOn(this)) "未选为模拟位置应用" else "未开启开发者模式"
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            tips += "未授予定位权限"
        }
        setupBanner.visibility = if (tips.isEmpty()) View.GONE else View.VISIBLE
        setupText.text = tips.joinToString("；")
    }

    private fun openSetup() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestRuntimePermissions()
        } else {
            showSetupDialog()
        }
    }

    private fun showSetupDialog(brand: SetupGuide.Brand = SetupGuide.detect()) {
        val devOn = SetupGuide.isDeveloperModeOn(this)
        AlertDialog.Builder(this)
            .setTitle("设置模拟位置应用")
            .setMessage(SetupGuide.fullGuide(this, brand))
            .setPositiveButton(if (devOn) "打开开发者选项" else "打开关于手机") { _, _ ->
                if (devOn) {
                    if (!SetupGuide.openDeveloperOptions(this)) {
                        toast("无法直接打开，请按步骤手动进入：${SetupGuide.developerOptionsPath(brand)}")
                    }
                } else {
                    try {
                        startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
            }
            .setNeutralButton("不是这个品牌？") { _, _ ->
                val brands = SetupGuide.Brand.entries
                AlertDialog.Builder(this)
                    .setTitle("选择手机品牌")
                    .setItems(brands.map { it.label }.toTypedArray()) { _, i -> showSetupDialog(brands[i]) }
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 把诊断信息发出去（微信、邮件、备忘录都行）。 */
    private fun shareDiagnostics() {
        val text = MockLocationService.diagnostics()
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "FakeGps诊断日志")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(i, "导出诊断日志"))
        } catch (_: Exception) {
            AlertDialog.Builder(this).setTitle("诊断日志").setMessage(text).setPositiveButton("关闭", null).show()
        }
    }

    // ---------------- 工具 ----------------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun fmt(lat: Double, lng: Double) = String.format(Locale.US, "%.6f,%.6f", lat, lng)

    companion object {
        /** 解析 "纬度,经度"，支持中英文逗号和空格分隔。 */
        fun parseCoord(text: String): Pair<Double, Double>? {
            val parts = text.trim().split(Regex("[,，\\s]+")).filter { it.isNotEmpty() }
            if (parts.size != 2) return null
            val lat = parts[0].toDoubleOrNull() ?: return null
            val lng = parts[1].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            return lat to lng
        }
    }
}
