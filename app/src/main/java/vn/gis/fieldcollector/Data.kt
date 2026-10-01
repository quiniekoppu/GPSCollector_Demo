package vn.gis.fieldcollector

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/** pts: "lon,lat;lon,lat" ; attrs: JSON thuộc tính riêng ; photos: đường dẫn ngăn bởi '|' */
@Entity(tableName = "features")
data class Feature(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val geomType: String, val subType: String, val code: String,
    val name: String = "", val street: String = "", val note: String = "",
    val pts: String, val lengthM: Double = 0.0, val areaM2: Double = 0.0, val perimM: Double = 0.0,
    val attrs: String = "{}", val photos: String = "", val updatedAt: Long = System.currentTimeMillis()
)

@Dao interface FeatureDao {
    @Query("SELECT * FROM features ORDER BY updatedAt DESC") fun all(): Flow<List<Feature>>
    @Upsert suspend fun upsert(f: Feature)
    @Delete suspend fun delete(f: Feature)
}

@Database(entities = [Feature::class], version = 1, exportSchema = false)
abstract class Db : RoomDatabase() {
    abstract fun dao(): FeatureDao
    companion object {
        @Volatile private var i: Db? = null
        fun get(c: Context) = i ?: synchronized(this) {
            i ?: Room.databaseBuilder(c.applicationContext, Db::class.java, "field.db").build().also { i = it }
        }
    }
}
