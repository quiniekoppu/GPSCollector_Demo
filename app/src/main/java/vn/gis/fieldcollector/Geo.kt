package vn.gis.fieldcollector

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

data class LL(val lat: Double, val lon: Double)

object GeoCalc {
    private const val R = 6_371_008.8
    fun dist(a: LL, b: LL): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat)
        val h = sin((p2 - p1) / 2).pow(2) + cos(p1) * cos(p2) * sin(Math.toRadians(b.lon - a.lon) / 2).pow(2)
        return 2 * R * asin(sqrt(h))
    }
    fun length(p: List<LL>) = p.zipWithNext { a, b -> dist(a, b) }.sum()
    fun perimeter(p: List<LL>) = if (p.size < 2) 0.0 else length(p) + dist(p.last(), p.first())
    fun area(p: List<LL>): Double {
        if (p.size < 3) return 0.0
        var s = 0.0
        for (i in p.indices) {
            val a = p[i]; val b = p[(i + 1) % p.size]
            s += Math.toRadians(b.lon - a.lon) * (2 + sin(Math.toRadians(a.lat)) + sin(Math.toRadians(b.lat)))
        }
        return abs(s * R * R / 2)
    }
    fun fmtLen(m: Double) = if (m >= 1000) "%.3f km".format(m / 1000) else "%.1f m".format(m)
    fun fmtArea(m: Double) = if (m >= 10000) "%.4f ha".format(m / 10000) else "%.1f m²".format(m)
}

fun parsePts(s: String) = s.split(";").filter { it.isNotBlank() }.map { val (lo, la) = it.split(","); LL(la.toDouble(), lo.toDouble()) }
fun serPts(l: List<LL>) = l.joinToString(";") { "${it.lon},${it.lat}" }

fun coord(p: LL) = JSONArray().put(p.lon).put(p.lat)
fun geom(type: String, p: List<LL>): JSONObject = when (type) {
    "POINT" -> JSONObject().put("type", "Point").put("coordinates", coord(p[0]))
    "LINE" -> JSONObject().put("type", "LineString").put("coordinates", JSONArray().also { a -> p.forEach { a.put(coord(it)) } })
    else -> JSONObject().put("type", "Polygon").put("coordinates",
        JSONArray().put(JSONArray().also { a -> (p + p[0]).forEach { a.put(coord(it)) } }))
}

val COLORS = mapOf("HO_VAN" to "#1E88E5", "TRU_DEN" to "#FB8C00", "CAY_XANH" to "#2E7D32",
    "BIEN_BAO" to "#D81B60", "DUONG" to "#6D4C41", "VUNG" to "#8E24AA")

/** full=false: dùng hiển thị bản đồ; full=true: xuất file đầy đủ thuộc tính */
fun toGeoJson(list: List<Feature>, full: Boolean): String {
    val arr = JSONArray()
    list.forEach { f ->
        val pr = JSONObject().put("color", COLORS[f.subType] ?: "#333333")
        if (full) {
            pr.put("subType", f.subType).put("code", f.code).put("name", f.name).put("street", f.street)
                .put("note", f.note).put("lengthM", f.lengthM).put("areaM2", f.areaM2).put("perimeterM", f.perimM)
            val a = JSONObject(f.attrs); a.keys().forEach { k -> pr.put(k, a.get(k)) }
        }
        arr.put(JSONObject().put("type", "Feature").put("geometry", geom(f.geomType, parsePts(f.pts))).put("properties", pr))
    }
    return JSONObject().put("type", "FeatureCollection").put("features", arr).toString()
}

fun draftJson(type: String, p: List<LL>): String {
    val arr = JSONArray()
    p.forEach { arr.put(JSONObject().put("type", "Feature").put("properties", JSONObject()).put("geometry", geom("POINT", listOf(it)))) }
    if (p.size >= 2) arr.put(JSONObject().put("type", "Feature").put("properties", JSONObject())
        .put("geometry", geom("LINE", if (type == "POLYGON") p + p[0] else p)))
    return JSONObject().put("type", "FeatureCollection").put("features", arr).toString()
}

/** Nhập GeoJSON (Point / LineString / Polygon). */
fun fromGeoJson(text: String, nextCode: (String) -> String): List<Feature> {
    val out = mutableListOf<Feature>()
    val arr = JSONObject(text).optJSONArray("features") ?: return out
    for (i in 0 until arr.length()) {
        val f = arr.getJSONObject(i); val g = f.optJSONObject("geometry") ?: continue
        val pr = f.optJSONObject("properties") ?: JSONObject()
        val (type, ring) = when (g.getString("type")) {
            "Point" -> "POINT" to JSONArray().put(g.getJSONArray("coordinates"))
            "LineString" -> "LINE" to g.getJSONArray("coordinates")
            "Polygon" -> "POLYGON" to g.getJSONArray("coordinates").getJSONArray(0)
            else -> continue
        }
        var pts = (0 until ring.length()).map { ring.getJSONArray(it).let { c -> LL(c.getDouble(1), c.getDouble(0)) } }
        if (type == "POLYGON" && pts.size > 1 && pts.first() == pts.last()) pts = pts.dropLast(1)
        val sub = pr.optString("subType").takeIf { SCHEMAS.containsKey(it) } ?: when (type) { "LINE" -> "DUONG"; "POLYGON" -> "VUNG"; else -> "HO_VAN" }
        val skip = setOf("subType", "code", "name", "street", "note", "lengthM", "areaM2", "perimeterM", "color")
        val at = JSONObject(); pr.keys().forEach { k -> if (k !in skip) at.put(k, pr.get(k).toString()) }
        out += Feature(geomType = type, subType = sub, code = pr.optString("code").ifBlank { nextCode(sub) },
            name = pr.optString("name"), street = pr.optString("street"), note = pr.optString("note"),
            pts = serPts(pts), lengthM = GeoCalc.length(pts), attrs = at.toString(),
            areaM2 = if (type == "POLYGON") GeoCalc.area(pts) else 0.0,
            perimM = if (type == "POLYGON") GeoCalc.perimeter(pts) else 0.0)
    }
    return out
}

// ---------------- Schema form ----------------
data class F(val key: String, val label: String, val kind: Char = 't', val opts: List<String> = emptyList(),
             val req: Boolean = false, val unit: String = "", val min: Double? = null, val max: Double? = null)
data class Sch(val title: String, val type: String, val prefix: String, val fields: List<F>)

val SCHEMAS = linkedMapOf(
    "HO_VAN" to Sch("Hố van", "POINT", "HV", listOf(
        F("loaiVan", "Loại van"), F("duongKinh", "Đường kính", 'n', unit = "mm", min = 0.0),
        F("vatLieu", "Vật liệu", 'c', listOf("Gang", "Thép", "Đồng", "Nhựa HDPE", "Khác")),
        F("doSau", "Độ sâu / chiều cao nắp hố", 'n', unit = "m", min = 0.0))),
    "TRU_DEN" to Sch("Trụ đèn / điện", "POINT", "TD", listOf(
        F("loaiTru", "Loại trụ", 'c', listOf("Cột điện hạ thế", "Cột điện trung thế", "Cột đèn chiếu sáng công cộng", "Cột hỗn hợp"), true),
        F("vatLieu", "Vật liệu", 'c', listOf("Bê tông ly tâm", "Thép mạ kẽm", "Gỗ", "Composite")),
        F("chieuCao", "Chiều cao trụ", 'n', unit = "m", min = 0.0, max = 100.0),
        F("tinhTrang", "Tình trạng", 'c', listOf("Đứng vững", "Nghiêng", "Nứt vỡ thân", "Hoen gỉ")))),
    "CAY_XANH" to Sch("Cây xanh", "POINT", "CX", listOf(
        F("tenCay", "Tên cây", req = true), F("dkThan", "Đường kính thân", 'n', unit = "cm", min = 0.0),
        F("chieuCao", "Chiều cao cây", 'n', unit = "m", min = 0.0), F("dkTan", "Đường kính tán", 'n', unit = "m", min = 0.0),
        F("loaiCay", "Loại cây", 'c', listOf("Cây bóng mát", "Cây cảnh quan", "Cây cổ thụ", "Cây nguy hiểm")))),
    "BIEN_BAO" to Sch("Biển báo", "POINT", "BB", listOf(
        F("loaiBien", "Loại biển", 'c', listOf("Biển cấm", "Biển nguy hiểm và cảnh báo", "Biển hiệu lệnh", "Biển chỉ dẫn", "Biển phụ"), true),
        F("maHieu", "Mã hiệu (P.102, W.201...)"),
        F("hinhDang", "Hình dạng", 'c', listOf("Hình tròn", "Hình tam giác đều", "Hình vuông", "Hình chữ nhật", "Hình bát giác")),
        F("tinhTrang", "Tình trạng", 'c', listOf("Rõ nét", "Mờ phai màu", "Bị che khuất", "Cong vênh", "Mất mặt biển")))),
    "DUONG" to Sch("Tuyến / Đường", "LINE", "TU", listOf(
        F("vatLieu", "Vật liệu", 'c', listOf("Nhựa Asphalt", "Bê tông xi măng", "Nhựa HDPE", "Gang", "Thép")),
        F("kichThuoc", "Kích thước (DN / tiết diện cống)"),
        F("tinhTrang", "Tình trạng", 'c', listOf("Đang sử dụng tốt", "Đang duy tu/sửa chữa", "Xuống cấp", "Ngưng khai thác")))),
    "VUNG" to Sch("Vùng", "POLYGON", "VG", emptyList())
)

fun validate(sc: Sch, c: Map<String, String>, a: Map<String, String>): Map<String, String> {
    val e = mutableMapOf<String, String>()
    if (c["name"].isNullOrBlank()) e["name"] = "Nhập tên đối tượng"
    sc.fields.forEach { f ->
        val v = a[f.key].orEmpty().trim()
        if (f.req && v.isEmpty()) e[f.key] = "Bắt buộc"
        if (f.kind == 'n' && v.isNotEmpty()) {
            val n = v.toDoubleOrNull()
            if (n == null) e[f.key] = "Phải là số"
            else if (f.min != null && n < f.min) e[f.key] = "Tối thiểu ${f.min}"
            else if (f.max != null && n > f.max) e[f.key] = "Tối đa ${f.max}"
        }
    }
    return e
}
