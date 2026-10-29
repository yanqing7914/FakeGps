package com.freelocation.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    @Test
    fun gcjRoundTripIsPrecise() {
        // 天安门 GCJ-02
        val gcjLat = 39.908722
        val gcjLng = 116.397499
        val wgs = CoordConverter.gcj02ToWgs84(gcjLat, gcjLng)
        val back = CoordConverter.wgs84ToGcj02(wgs.lat, wgs.lng)
        assertEquals(gcjLat, back.lat, 1e-7)
        assertEquals(gcjLng, back.lng, 1e-7)
        // 国内偏移量应在几百米量级
        val offset = GeoMath.distance(GeoMath.Point(gcjLat, gcjLng), GeoMath.Point(wgs.lat, wgs.lng))
        assertTrue("offset=$offset", offset in 100.0..800.0)
    }

    @Test
    fun outsideChinaUnchanged() {
        val p = CoordConverter.wgs84ToGcj02(48.8584, 2.2945) // 巴黎
        assertEquals(48.8584, p.lat, 0.0)
        assertEquals(2.2945, p.lng, 0.0)
    }

    @Test
    fun bd09RoundTrip() {
        val bd = CoordConverter.gcj02ToBd09(31.2397, 121.4998)
        val gcj = CoordConverter.bd09ToGcj02(bd.lat, bd.lng)
        assertEquals(31.2397, gcj.lat, 1e-5)
        assertEquals(121.4998, gcj.lng, 1e-5)
    }

    @Test
    fun moveAndDistanceAgree() {
        val start = GeoMath.Point(31.0, 121.0)
        val moved = GeoMath.move(start, 90.0, 1000.0)
        assertEquals(1000.0, GeoMath.distance(start, moved), 0.5)
        assertEquals(90.0, GeoMath.bearing(start, moved), 0.1)
    }

    @Test
    fun positionAlongRoute() {
        val a = GeoMath.Point(31.0, 121.0)
        val b = GeoMath.move(a, 0.0, 100.0)
        val c = GeoMath.move(b, 90.0, 100.0)
        val route = listOf(a, b, c)
        assertEquals(200.0, GeoMath.routeLength(route), 0.5)

        val (mid1, br1) = GeoMath.positionAlong(route, 50.0)
        assertEquals(50.0, GeoMath.distance(a, mid1), 0.5)
        assertEquals(0.0, br1, 0.5)

        val (mid2, br2) = GeoMath.positionAlong(route, 150.0)
        assertEquals(50.0, GeoMath.distance(b, mid2), 0.5)
        assertEquals(90.0, br2, 0.5)

        val (end, _) = GeoMath.positionAlong(route, 999.0)
        assertEquals(0.0, GeoMath.distance(c, end), 0.01)
    }

    @Test
    fun detectBrand() {
        assertEquals(SetupGuide.Brand.XIAOMI, SetupGuide.detect("Xiaomi", "Xiaomi"))
        assertEquals(SetupGuide.Brand.XIAOMI, SetupGuide.detect("Xiaomi", "Redmi"))
        assertEquals(SetupGuide.Brand.HUAWEI, SetupGuide.detect("HUAWEI", "HUAWEI"))
        // 老荣耀：manufacturer 是 HUAWEI，brand 是 HONOR
        assertEquals(SetupGuide.Brand.HONOR, SetupGuide.detect("HUAWEI", "HONOR"))
        assertEquals(SetupGuide.Brand.HONOR, SetupGuide.detect("HONOR", "HONOR"))
        assertEquals(SetupGuide.Brand.OPPO, SetupGuide.detect("OnePlus", "OnePlus"))
        assertEquals(SetupGuide.Brand.VIVO, SetupGuide.detect("vivo", "iQOO"))
        assertEquals(SetupGuide.Brand.SAMSUNG, SetupGuide.detect("samsung", "samsung"))
        assertEquals(SetupGuide.Brand.OTHER, SetupGuide.detect("Google", "google"))
        assertTrue(SetupGuide.developerOptionsPath(SetupGuide.Brand.HUAWEI).contains("系统和更新"))
    }

    @Test
    fun parsePhotonResults() {
        val body = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[121.487632,31.2353356]},
               "properties":{"name":"外滩","city":"黄浦区","state":"上海市","country":"中国"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[121.487632,31.2353356]},
               "properties":{"name":"外滩","city":"黄浦区","state":"上海市"}},
              {"type":"Feature","geometry":{"type":"Point","coordinates":[]},"properties":{"name":"坏数据"}}
            ]}
        """.trimIndent()
        val r = PlaceSearch.parse(body)
        assertEquals(1, r.size)
        assertEquals("外滩", r[0].name)
        assertEquals("上海市 黄浦区", r[0].address)
        assertEquals(31.2353356, r[0].wgsLat, 1e-9)
        assertEquals(121.487632, r[0].wgsLng, 1e-9)
        assertTrue(PlaceSearch.parse("{}").isEmpty())
    }

    @Test
    fun parseCoordFormats() {
        assertEquals(39.9 to 116.4, MainActivity.parseCoord("39.9,116.4"))
        assertEquals(39.9 to 116.4, MainActivity.parseCoord(" 39.9， 116.4 "))
        assertEquals(39.9 to 116.4, MainActivity.parseCoord("39.9 116.4"))
        assertNotNull(MainActivity.parseCoord("-33.86,151.21"))
        assertNull(MainActivity.parseCoord("abc"))
        assertNull(MainActivity.parseCoord("91,10"))
        assertNull(MainActivity.parseCoord("39.9"))
    }
}
