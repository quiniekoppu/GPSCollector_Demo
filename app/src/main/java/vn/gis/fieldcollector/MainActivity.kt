package vn.gis.fieldcollector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import java.io.File

private const val BASE = """{"version":8,"sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256}},"layers":[{"id":"osm","type":"raster","source":"osm"}]}"""

class MainActivity : ComponentActivity() {
    private val vm: VM by viewModels()
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        MapLibre.getInstance(this)
        setContent { MaterialTheme { App(vm) } }
    }
}

fun setupLayers(s: Style, ortho: File?) {
    ortho?.let {
        s.addSource(RasterSource("ortho", TileSet("2.2.0", "mbtiles://${it.absolutePath}"), 256))
        s.addLayer(RasterLayer("ortho-lyr", "ortho"))
    }
    val geo = { n: String -> GeoJsonSource(n).also { src -> src.setGeoJson("""{"type":"FeatureCollection","features":[]}""") } }
    listOf("feat", "draft", "me").forEach { s.addSource(geo(it)) }
    val col = Expression.get("color")
    fun isG(t: String) = Expression.eq(Expression.geometryType(), Expression.literal(t))
    s.addLayer(FillLayer("pg", "feat").withFilter(isG("Polygon")).withProperties(fillColor(col), fillOpacity(0.35f)))
    s.addLayer(LineLayer("ln", "feat").withProperties(lineColor(col), lineWidth(3f)))
    s.addLayer(CircleLayer("pt", "feat").withFilter(isG("Point")).withProperties(circleColor(col), circleRadius(7f), circleStrokeWidth(1.5f), circleStrokeColor("#FFFFFF")))
    s.addLayer(LineLayer("d-ln", "draft").withProperties(lineColor("#E91E63"), lineWidth(3f), lineDasharray(arrayOf(2f, 2f))))
    s.addLayer(CircleLayer("d-pt", "draft").withProperties(circleColor("#E91E63"), circleRadius(5f)))
    s.addLayer(CircleLayer("me-pt", "me").withProperties(circleColor("#2962FF"), circleRadius(8f), circleStrokeWidth(3f), circleStrokeColor("#FFFFFF")))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: VM) {
    val ctx = LocalContext.current
    val feats by vm.features.collectAsState()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var ready by remember { mutableStateOf(false) }
    var typeDlg by remember { mutableStateOf(false) }
    val mv = remember { MapView(ctx).also { it.onCreate(null) } }
    DisposableEffect(Unit) { mv.onStart(); mv.onResume(); onDispose { mv.onPause(); mv.onStop(); mv.onDestroy() } }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it.values.any { ok -> ok }) vm.toggleGps()
    }
    val orthoPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> u?.let { vm.importOrtho(it) } }
    val geoPick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        u?.let { ctx.contentResolver.openInputStream(it)?.bufferedReader()?.readText()?.let { t -> vm.importGeo(t) } }
    }
    fun export() {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", vm.exportFile())
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json")
            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Xuất GeoJSON"))
    }
    fun gpsClick() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) vm.toggleGps()
        else perm.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // đồng bộ dữ liệu lên bản đồ
    val draftPts = vm.draft.toList()
    LaunchedEffect(feats, draftPts, vm.loc, ready) {
        map?.getStyle { s ->
            s.getSourceAs<GeoJsonSource>("feat")?.setGeoJson(toGeoJson(feats, false))
            s.getSourceAs<GeoJsonSource>("draft")?.setGeoJson(draftJson(vm.mode ?: "POINT", draftPts))
            vm.loc?.let { l -> s.getSourceAs<GeoJsonSource>("me")?.setGeoJson(toGeoJson(listOf(
                Feature(geomType = "POINT", subType = "HO_VAN", code = "", pts = serPts(listOf(LL(l.latitude, l.longitude))))), false)) }
        }
    }
    LaunchedEffect(vm.orthoVer, ready) {
        if (ready) map?.getStyle { s ->
            if (s.getLayer("ortho-lyr") == null) vm.orthoFile()?.let { f ->
                s.addSource(RasterSource("ortho", TileSet("2.2.0", "mbtiles://${f.absolutePath}"), 256))
                s.addLayerBelow(RasterLayer("ortho-lyr", "ortho"), "pg")
            }
        }
    }
    LaunchedEffect(vm.orthoOn, ready) {
        map?.getStyle { s -> s.getLayer("ortho-lyr")?.setProperties(visibility(if (vm.orthoOn) "visible" else "none")) }
    }
    LaunchedEffect(vm.loc == null) { vm.loc?.let { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.latitude, it.longitude), 18.0)) } }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ mv.also { v -> v.getMapAsync { m ->
            map = m
            m.cameraPosition = CameraPosition.Builder().target(LatLng(10.7769, 106.7009)).zoom(14.0).build()
            m.setStyle(Style.Builder().fromJson(BASE)) { s -> setupLayers(s, vm.orthoFile()); ready = true }
            m.addOnMapClickListener { vm.onTap(it.latitude, it.longitude); true }
        } } }, Modifier.fillMaxSize())

        Column(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) {
            vm.loc?.let { Surface(tonalElevation = 4.dp) { Text("GPS ±%.1f m".format(it.accuracy), Modifier.padding(6.dp)) } }
            if (vm.mode != null) Surface(tonalElevation = 4.dp) {
                Text("${SCHEMAS[vm.sub]?.title}: " + (if (vm.mode == "POINT") "chạm bản đồ hoặc Ghim GPS" else vm.measure()), Modifier.padding(6.dp)) }
        }

        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(8.dp)) {
            if (vm.mode != null) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { if (vm.gps) vm.pinGps() else gpsClick() }) { Text("Ghim GPS") }
                if (vm.mode == "LINE") FilterChip(vm.tracking, { if (vm.gps) vm.tracking = !vm.tracking else gpsClick() }, { Text("Tracking") })
                if (vm.mode != "POINT") { OutlinedButton({ vm.undo() }) { Text("Hoàn tác") }; Button({ vm.finish() }) { Text("Hoàn tất") } }
                OutlinedButton({ vm.cancel() }) { Text("Hủy") }
            }
            Spacer(Modifier.height(6.dp))
            Surface(tonalElevation = 6.dp) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button({ gpsClick() }) { Text(if (vm.gps) "Tắt GPS" else "Bật GPS") }
                    Button({ if (vm.orthoFile() == null) orthoPick.launch(arrayOf("*/*")) else vm.orthoOn = !vm.orthoOn }) { Text(if (vm.orthoFile() == null) "Tải ảnh Ortho" else if (vm.orthoOn) "Ẩn Ortho" else "Hiện Ortho") }
                    Button({ typeDlg = true }) { Text("Thêm Điểm") }
                    Button({ vm.start("LINE", "DUONG") }) { Text("Vẽ Tuyến") }
                    Button({ vm.start("POLYGON", "VUNG") }) { Text("Vẽ Vùng") }
                    Button({ vm.showList = true }) { Text("Danh sách (${feats.size})") }
                }
            }
        }

        if (vm.showList) Surface(Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().padding(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    OutlinedButton({ vm.showList = false }) { Text("Đóng") }
                    Button({ export() }) { Text("Xuất GeoJSON") }
                    Button({ geoPick.launch(arrayOf("*/*")) }) { Text("Nhập GeoJSON") }
                    Button({ orthoPick.launch(arrayOf("*/*")) }) { Text("Đổi Ortho (.mbtiles)") }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(feats) { f ->
                        ListItem(headlineContent = { Text("${f.code} – ${f.name}") },
                            supportingContent = { Text(SCHEMAS[f.subType]?.title.orEmpty() + when (f.geomType) {
                                "LINE" -> " • " + GeoCalc.fmtLen(f.lengthM); "POLYGON" -> " • " + GeoCalc.fmtArea(f.areaM2); else -> "" }) },
                            modifier = Modifier.then(Modifier).let { it })
                        TextButton({ vm.editing = f; vm.showList = false }) { Text("Sửa") }
                        HorizontalDivider()
                    }
                }
            }
        }

        if (typeDlg) AlertDialog(onDismissRequest = { typeDlg = false }, confirmButton = {}, title = { Text("Chọn loại điểm") },
            text = { Column { SCHEMAS.filter { it.value.type == "POINT" }.forEach { (k, v) ->
                TextButton({ typeDlg = false; vm.start("POINT", k) }) { Text(v.title) } } } })

        vm.editing?.let { FormSheet(it, vm, feats.any { x -> x.id == it.id }) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormSheet(f: Feature, vm: VM, exists: Boolean) {
    val ctx = LocalContext.current
    val sc = SCHEMAS[f.subType]!!
    val c = remember(f.id) { mutableStateMapOf("code" to f.code, "name" to f.name, "street" to f.street, "note" to f.note) }
    val a = remember(f.id) { mutableStateMapOf<String, String>().also { m -> JSONObject(f.attrs).let { j -> j.keys().forEach { k -> m[k] = j.getString(k) } } } }
    val photos = remember(f.id) { mutableStateListOf<String>().also { it.addAll(f.photos.split("|").filter { s -> s.isNotBlank() }) } }
    var err by remember { mutableStateOf(mapOf<String, String>()) }
    var pending by remember { mutableStateOf<File?>(null) }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) pending?.let { photos += it.absolutePath } }
    val camPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { val file = File(ctx.getExternalFilesDir("photos"), "${f.id}_${System.currentTimeMillis()}.jpg").also { it.parentFile?.mkdirs() }
            pending = file; cam.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)) }
    }
    val p = parsePts(f.pts)

    ModalBottomSheet(onDismissRequest = { vm.cancel() }) {
        LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text(sc.title, style = MaterialTheme.typography.titleLarge) }
            item { Text(when (f.geomType) {
                "POINT" -> "Tọa độ: %.6f, %.6f".format(p[0].lat, p[0].lon)
                "LINE" -> "Chiều dài: " + GeoCalc.fmtLen(GeoCalc.length(p))
                else -> "Diện tích: ${GeoCalc.fmtArea(GeoCalc.area(p))} • Chu vi: ${GeoCalc.fmtLen(GeoCalc.perimeter(p))}" }) }
            item { OutlinedTextField(c["code"]!!, { c["code"] = it }, label = { Text("Mã đối tượng") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(c["name"]!!, { c["name"] = it }, label = { Text("Tên đối tượng *") }, isError = err["name"] != null,
                supportingText = { err["name"]?.let { Text(it) } }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(c["street"]!!, { c["street"] = it }, label = { Text("Đường") }, modifier = Modifier.fillMaxWidth()) }
            items(sc.fields) { fd ->
                val v = a[fd.key].orEmpty()
                if (fd.kind == 'c') {
                    var open by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text(fd.label + ": " + v.ifBlank { "chọn…" } + (if (fd.req) " *" else "")) }
                        DropdownMenu(open, { open = false }) { fd.opts.forEach { o -> DropdownMenuItem({ Text(o) }, { a[fd.key] = o; open = false }) } }
                    }
                    err[fd.key]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                } else OutlinedTextField(v, { a[fd.key] = it }, label = { Text(fd.label + (if (fd.unit.isNotEmpty()) " (${fd.unit})" else "") + (if (fd.req) " *" else "")) },
                    isError = err[fd.key] != null, supportingText = { err[fd.key]?.let { Text(it) } }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (fd.kind == 'n') KeyboardType.Decimal else KeyboardType.Text))
            }
            item { OutlinedTextField(c["note"]!!, { c["note"] = it }, label = { Text("Ghi chú") }, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedButton({ camPerm.launch(Manifest.permission.CAMERA) }) { Text("Chụp ảnh hiện trường (${photos.size})") } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ err = validate(sc, c, a); if (err.isEmpty()) vm.save(f, c, a, photos.toList()) }, Modifier.weight(1f)) { Text("Lưu") }
                if (exists) OutlinedButton({ vm.delete(f) }) { Text("Xóa") }
            } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
