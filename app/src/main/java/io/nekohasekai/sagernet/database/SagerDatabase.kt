package io.nekohasekai.sagernet.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.gson.GsonConverters

@Database(
    entities = [ProxyGroup::class, ProxyEntity::class, RuleEntity::class],
    version = 12,
    autoMigrations = [
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11, spec = SagerDatabase.RemoveNekoColumn::class),
        // v12: additive lifetimeRx/lifetimeTx columns on proxy_entities (default 0). Pure column
        // adds are auto-migratable without a spec; never destructive.
        AutoMigration(from = 11, to = 12),
    ],
)
@TypeConverters(value = [KryoConverters::class, GsonConverters::class])
abstract class SagerDatabase : RoomDatabase() {

    @DeleteColumn(tableName = "proxy_entities", columnName = "nekoBean")
    class RemoveNekoColumn : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            // Legacy neko-plugin rows are non-functional placeholders; without the
            // bean column they could no longer even render. Purge them.
            db.execSQL("DELETE FROM proxy_entities WHERE type = 999")
        }
    }

    companion object {
        val instance by lazy {
            SagerNet.application.getDatabasePath(Key.DB_PROFILE).parentFile?.mkdirs()
            Room.databaseBuilder(SagerNet.application, SagerDatabase::class.java, Key.DB_PROFILE)
                .setJournalMode(JournalMode.TRUNCATE)
                // Plan 027 Stage 3: the main-thread-DB allowance is behind a build flag so it can
                // be removed once the app runs StrictMode-clean (debug already ships with it off).
                .apply { if (BuildConfig.ALLOW_MAIN_THREAD_DB) allowMainThreadQueries() }
                .enableMultiInstanceInvalidation()
                .setQueryExecutor(DbExecutors.query)
                .build()
        }

        val groupDao get() = instance.groupDao()
        val proxyDao get() = instance.proxyDao()
        val rulesDao get() = instance.rulesDao()
    }

    abstract fun groupDao(): ProxyGroup.Dao
    abstract fun proxyDao(): ProxyEntity.Dao
    abstract fun rulesDao(): RuleEntity.Dao
}
