package com.freelocation.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 坐标系转换。
 *
 * - WGS84：GPS 原始坐标，Android 系统定位使用的坐标系，模拟定位必须推送这个。
 * - GCJ-02：国内"火星坐标"，高德地图、腾讯地图显示用的坐标系。
 *
 * 地图瓦片来自高德（GCJ-02），所以地图上点选的坐标要先转成 WGS84 再推给系统，
 * 否则在高德/微信里看到的位置会偏移几百米。
 */
object CoordConverter {
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    data class LatLng(val lat: Double, val lng: Double)

    /** 是否在中国境外（境外不做偏移）。 */
    fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    fun wgs84ToGcj02(lat: Double, lng: Double): LatLng {
        if (outOfChina(lat, lng)) return LatLng(lat, lng)
        val (dLat, dLng) = delta(lat, lng)
        return LatLng(lat + dLat, lng + dLng)
    }

    /**
     * GCJ-02 转 WGS84。用迭代法求逆，误差小于 1 厘米。
     */
    fun gcj02ToWgs84(lat: Double, lng: Double): LatLng {
        if (outOfChina(lat, lng)) return LatLng(lat, lng)
        var wLat = lat
        var wLng = lng
        repeat(10) {
            val g = wgs84ToGcj02(wLat, wLng)
            val dLat = g.lat - lat
            val dLng = g.lng - lng
            wLat -= dLat
            wLng -= dLng
            if (abs(dLat) < 1e-9 && abs(dLng) < 1e-9) return LatLng(wLat, wLng)
        }
        return LatLng(wLat, wLng)
    }

    private const val X_PI = PI * 3000.0 / 180.0

    /** 百度 BD-09 转 GCJ-02。 */
    fun bd09ToGcj02(lat: Double, lng: Double): LatLng {
        val x = lng - 0.0065
        val y = lat - 0.006
        val z = sqrt(x * x + y * y) - 0.00002 * sin(y * X_PI)
        val theta = kotlin.math.atan2(y, x) - 0.000003 * cos(x * X_PI)
        return LatLng(z * sin(theta), z * cos(theta))
    }

    /** GCJ-02 转百度 BD-09。 */
    fun gcj02ToBd09(lat: Double, lng: Double): LatLng {
        val z = sqrt(lng * lng + lat * lat) + 0.00002 * sin(lat * X_PI)
        val theta = kotlin.math.atan2(lat, lng) + 0.000003 * cos(lng * X_PI)
        return LatLng(z * sin(theta) + 0.006, z * cos(theta) + 0.0065)
    }

    private fun delta(lat: Double, lng: Double): Pair<Double, Double> {
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return dLat to dLng
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
