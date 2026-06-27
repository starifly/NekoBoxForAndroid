package io.nekohasekai.sagernet.database.preference

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DbExecutors

@Database(entities = [KeyValuePair::class], version = 1)
abstract class PublicDatabase : RoomDatabase() {
    companion object {
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            Room.databaseBuilder(SagerNet.application, PublicDatabase::class.java, Key.DB_PUBLIC)
                .setJournalMode(JournalMode.TRUNCATE)
                .enableMultiInstanceInvalidation()
                .setQueryExecutor(DbExecutors.query)
                .build()
        }

        val kvPairDao get() = instance.keyValuePairDao()

        /** Exposed for the cached [RoomPreferenceDataStore] to observe `KeyValuePair`
         *  invalidations (cross-process refresh). */
        val database get() = instance
    }

    abstract fun keyValuePairDao(): KeyValuePair.Dao
}
