package com.freelocation.app

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 地理计算：距离、方位角、按方向移动、沿路线插值。坐标均为 WGS84。 */
object GeoMath {
    private const val EARTH_RADIUS = 6371000.0

    data class Point(val lat: Double, val lng: Double)

    /** 两点间球面距离（米）。 */
    fun distance(a: Point, b: Point): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * EARTH_RADIUS * asin(sqrt(h))
    }

    /** a 指向 b 的方位角（0~360，正北为 0，顺时针）。 */
    fun bearing(a: Point, b: Point): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** 从 p 出发，朝 bearingDeg 方向走 meters 米后的位置。 */
    fun move(p: Point, bearingDeg: Double, meters: Double): Point {
        val d = meters / EARTH_RADIUS
        val br = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(p.lat)
        val lng1 = Math.toRadians(p.lng)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(br))
        val lng2 = lng1 + atan2(sin(br) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return Point(Math.toDegrees(lat2), (Math.toDegrees(lng2) + 540) % 360 - 180)
    }

    /** 路线总长度（米）。 */
    fun routeLength(route: List<Point>): Double =
        route.zipWithNext().sumOf { (a, b) -> distance(a, b) }

    /**
     * 返回沿路线走了 traveled 米后的位置和当前朝向。
     * traveled 超过总长度时停在终点。
     */
    fun positionAlong(route: List<Point>, traveled: Double): Pair<Point, Double> {
        require(route.isNotEmpty()) { "route is empty" }
        if (route.size == 1) return route[0] to 0.0
        var remaining = traveled.coerceAtLeast(0.0)
        for ((a, b) in route.zipWithNext()) {
            val seg = distance(a, b)
            if (remaining <= seg) {
                val br = bearing(a, b)
                return (if (seg == 0.0) a else move(a, br, remaining)) to br
            }
            remaining -= seg
        }
        val last = route.last()
        return last to bearing(route[route.size - 2], last)
    }
}
