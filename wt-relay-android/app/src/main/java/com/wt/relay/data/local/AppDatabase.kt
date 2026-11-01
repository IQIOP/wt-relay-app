package com.wt.relay.data.local

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "queued_notifications")
data class QueuedNotification(
    @PrimaryKey val eventId: String,
    val packageName: String,
    val senderTitle: String,
    val messageText: String,
    val receivedAt: String,
    val status: String, // PENDING, DELIVERED, FAILED
    val retryCount: Int = 0
)

@Dao
interface NotificationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: QueuedNotification): Long

    @Query("SELECT * FROM queued_notifications WHERE status != 'DELIVERED' ORDER BY receivedAt ASC LIMIT 50")
    suspend fun getPendingEvents(): List<QueuedNotification>

    @Query("UPDATE queued_notifications SET status = :status, retryCount = retryCount + 1 WHERE eventId = :eventId")
    suspend fun updateStatus(eventId: String, status: String)

    @Query("SELECT * FROM queued_notifications ORDER BY receivedAt DESC LIMIT 50")
    fun observeRecentEvents(): Flow<List<QueuedNotification>>
}

@Database(entities = [QueuedNotification::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun notificationDao(): NotificationDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun get(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "wt_relay_queue.db")
                    .build().also { INSTANCE = it }
            }
        }
    }
}
