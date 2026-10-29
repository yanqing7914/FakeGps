package com.freelocation.app

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 地点搜索。使用 Photon（基于 OpenStreetMap 的公开地理编码服务），无需 API Key。
 *
 * 注意：搜索关键词会发送到 photon.komoot.io。返回坐标为 WGS84。
 */
object PlaceSearch {
    data class Result(val name: String, val address: String, val wgsLat: Double, val wgsLng: Double)

    private const val ENDPOINT = "https://photon.komoot.io/api/"

    /** 阻塞调用，必须在后台线程执行。biasLat/biasLng 用于优先返回附近的结果。 */
    @Throws(IOException::class)
    fun search(query: String, biasLat: Double? = null, biasLng: Double? = null): List<Result> {
        val sb = StringBuilder(ENDPOINT)
            .append("?q=").append(URLEncoder.encode(query, "UTF-8"))
            .append("&limit=15")
        if (biasLat != null && biasLng != null) sb.append("&lat=").append(biasLat).append("&lon=").append(biasLng)

        val conn = URL(sb.toString()).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 10000
        conn.setRequestProperty("User-Agent", "FakeGps/1.5.0 (Android)")
        conn.setRequestProperty("Accept", "application/json")
        try {
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }

    /** 解析 Photon 返回的 GeoJSON。 */
    fun parse(body: String): List<Result> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        val out = mutableListOf<Result>()
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val coords = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            if (coords.length() < 2) continue
            val lng = coords.optDouble(0)
            val lat = coords.optDouble(1)
            if (lat.isNaN() || lng.isNaN()) continue

            val p = f.optJSONObject("properties") ?: JSONObject()
            val name = p.optString("name").ifEmpty { p.optString("street") }.ifEmpty { "未命名地点" }
            val address = listOf("state", "city", "district", "locality", "street", "housenumber")
                .map { p.optString(it) }
                .filter { it.isNotEmpty() && it != name }
                .distinct()
                .joinToString(" ")
            out += Result(name, address, lat, lng)
        }
        // 同名同址的结果只保留一个
        return out.distinctBy { it.name + "|" + it.address }
    }
}
