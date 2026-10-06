package com.syntmusic.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recent")
data class RecentEntry(
    @PrimaryKey val trackId: Long,
    val playedAt: Long,
)

@Dao
interface RecentDao {
    @Query("SELECT trackId FROM recent ORDER BY playedAt DESC LIMIT :limit")
    fun observeIds(limit: Int): Flow<List<Long>>

    @Upsert
    suspend fun upsert(entry: RecentEntry)

    @Query("DELETE FROM recent WHERE trackId NOT IN (SELECT trackId FROM recent ORDER BY playedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Database(entities = [RecentEntry::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recentDao(): RecentDao
}
