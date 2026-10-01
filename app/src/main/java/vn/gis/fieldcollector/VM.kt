package vn.gis.fieldcollector

import android.annotation.SuppressLint
import android.app.Application
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class VM(app: Application) : AndroidViewModel(app) {
    private val dao = Db.get(app).dao()
    val features = dao.all().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    var mode by mutableStateOf<String?>(null)          // POINT / LINE / POLYGON
    var sub by mutableStateOf("")
    val draft = mutableStateListOf<LL>()
    var editing by mutableStateOf<Feature?>(null)
    var showList by mutableStateOf(false)
    var pickPoint by mutableStateOf(false)
    var loc by mutableStateOf<Location?>(null)
    var gps by mutableStateOf(false)
    var tracking by mutableStateOf(false)
    var orthoOn by mutableStateOf(true)
    var orthoVer by mutableIntStateOf(0)

    private val lm = app.getSystemService(LocationManager::class.java)
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            loc = l
            if (tracking && mode == "LINE" && l.accuracy <= 15f) {
                val p = LL(l.latitude, l.longitude)
                if (draft.isEmpty() || GeoCalc.dist(draft.last(), p) >= 2) draft += p
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun toggleGps() {
        if (gps) { lm.removeUpdates(listener); gps = false; tracking = false }
        else { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener); gps = true }
    }

    fun orthoFile() = File(getApplication<Application>().filesDir, "ortho.mbtiles").takeIf { it.exists() }
    fun importOrtho(uri: android.net.Uri) {
        val ctx = getApplication<Application>()
        File(ctx.filesDir, "ortho.mbtiles").outputStream().use { o -> ctx.contentResolver.openInputStream(uri)?.use { it.copyTo(o) } }
        orthoVer++
    }

    fun start(type: String, s: String) { mode = type; sub = s; draft.clear(); showList = false; pickPoint = false }
    fun cancel() { mode = null; draft.clear(); editing = null; tracking = false }

    fun addVertex(p: LL) {
        when (mode) {
            "POINT" -> { draft.clear(); draft += p; finish() }
            "LINE", "POLYGON" -> draft += p
        }
    }
    fun onTap(lat: Double, lon: Double) {
        if (mode != null) { addVertex(LL(lat, lon)); return }
        val t = LL(lat, lon)
        features.value.minByOrNull { f -> parsePts(f.pts).minOf { GeoCalc.dist(it, t) } }
            ?.takeIf { f -> parsePts(f.pts).minOf { GeoCalc.dist(it, t) } < 30 }?.let { editing = it }
    }
    fun pinGps() { loc?.let { addVertex(LL(it.latitude, it.longitude)) } }
    fun undo() { draft.removeLastOrNull() }

    fun measure(): String = when (mode) {
        "LINE" -> "Dài: " + GeoCalc.fmtLen(GeoCalc.length(draft))
        "POLYGON" -> "S: " + GeoCalc.fmtArea(GeoCalc.area(draft)) + " • CV: " + GeoCalc.fmtLen(GeoCalc.perimeter(draft))
        else -> ""
    }

    fun nextCode(s: String) = "${SCHEMAS[s]!!.prefix}-%04d".format(features.value.count { it.subType == s } + 1)

    fun finish() {
        val need = when (mode) { "POINT" -> 1; "LINE" -> 2; else -> 3 }
        if (mode == null || draft.size < need) return
        editing = Feature(geomType = mode!!, subType = sub, code = nextCode(sub), pts = serPts(draft.toList()))
    }

    fun save(f: Feature, c: Map<String, String>, a: Map<String, String>, photos: List<String>) = viewModelScope.launch {
        val p = parsePts(f.pts)
        dao.upsert(f.copy(code = c["code"].orEmpty().ifBlank { nextCode(f.subType) }, name = c["name"].orEmpty(),
            street = c["street"].orEmpty(), note = c["note"].orEmpty(),
            lengthM = if (f.geomType == "LINE") GeoCalc.length(p) else 0.0,
            areaM2 = if (f.geomType == "POLYGON") GeoCalc.area(p) else 0.0,
            perimM = if (f.geomType == "POLYGON") GeoCalc.perimeter(p) else 0.0,
            attrs = JSONObject(a.filterValues { it.isNotBlank() }).toString(),
            photos = photos.joinToString("|"), updatedAt = System.currentTimeMillis()))
        cancel()
    }
    fun delete(f: Feature) = viewModelScope.launch { dao.delete(f); cancel() }

    fun exportFile(): File {
        val d = File(getApplication<Application>().cacheDir, "export").apply { mkdirs() }
        return File(d, "data_${System.currentTimeMillis()}.geojson").also { it.writeText(toGeoJson(features.value, true)) }
    }
    fun importGeo(text: String) = viewModelScope.launch {
        val counters = mutableMapOf<String, Int>()
        fromGeoJson(text) { s -> counters[s] = (counters[s] ?: features.value.count { it.subType == s }) + 1
            "${SCHEMAS[s]!!.prefix}-%04d".format(counters[s]) }.forEach { dao.upsert(it) }
    }
}
