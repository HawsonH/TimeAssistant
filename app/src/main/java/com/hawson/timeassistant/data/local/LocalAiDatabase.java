package com.hawson.timeassistant.data.local;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {PreferenceRuleEntity.class, BehaviorEventEntity.class,
        ToolExecutionEntity.class, ProactiveRunEntity.class}, version = 2, exportSchema = false)
public abstract class LocalAiDatabase extends RoomDatabase {
    private static volatile LocalAiDatabase instance;

    public abstract LocalAiDao dao();

    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `proactive_runs` "
                    + "(`id` TEXT NOT NULL, `routineType` TEXT NOT NULL, `localDay` INTEGER NOT NULL, "
                    + "`scheduledAt` INTEGER NOT NULL, `startedAt` INTEGER NOT NULL, "
                    + "`finishedAt` INTEGER NOT NULL, `fingerprint` TEXT NOT NULL, "
                    + "`status` TEXT NOT NULL, `chatMessageId` TEXT, "
                    + "`notificationSentAt` INTEGER NOT NULL, `summary` TEXT NOT NULL, "
                    + "`error` TEXT NOT NULL, PRIMARY KEY(`id`))");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_proactive_runs_localDay_routineType` "
                    + "ON `proactive_runs` (`localDay`, `routineType`)");
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_proactive_runs_localDay_fingerprint` "
                    + "ON `proactive_runs` (`localDay`, `fingerprint`)");
        }
    };

    public static LocalAiDatabase get(Context context) {
        if (instance == null) {
            synchronized (LocalAiDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(context.getApplicationContext(),
                            LocalAiDatabase.class, "time_assistant_local_ai.db")
                            .addMigrations(MIGRATION_1_2)
                            .build();
                }
            }
        }
        return instance;
    }
}
