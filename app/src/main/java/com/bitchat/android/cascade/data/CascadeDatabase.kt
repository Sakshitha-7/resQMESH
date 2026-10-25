package com.bitchat.android.cascade.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName="facts")
data class StoredFact(@PrimaryKey val id: String, val frame: String, val receivedAt: Long)
@Dao
interface FactDao {
    @Query("SELECT * FROM facts ORDER BY receivedAt, id") fun observe(): Flow<List<StoredFact>>
    @Query("SELECT * FROM facts ORDER BY receivedAt, id") suspend fun all(): List<StoredFact>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insert(fact: StoredFact)
    @Query("DELETE FROM facts") suspend fun wipe()
}
@Database(entities=[StoredFact::class],version=1,exportSchema=false)
abstract class CascadeDatabase : RoomDatabase() { abstract fun facts(): FactDao }
