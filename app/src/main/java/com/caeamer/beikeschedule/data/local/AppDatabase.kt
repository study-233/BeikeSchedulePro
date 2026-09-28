package com.caeamer.beikeschedule.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CourseEntity::class, SectionTimeEntity::class, GradeEntity::class, ExamEntity::class, TodoEntity::class, ScheduleEntity::class, ScheduleStateEntity::class, NoticeEntity::class, NoticeFeedEntity::class],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun scheduleDao(): ScheduleDao
    abstract fun courseDao(): CourseDao
    abstract fun sectionTimeDao(): SectionTimeDao
    abstract fun gradeDao(): GradeDao
    abstract fun examDao(): ExamDao
    abstract fun todoDao(): TodoDao
    abstract fun noticeDao(): NoticeDao

    companion object {
        /** v1 → v2：新增 grade 表（课程/节次数据原样保留）。 */
        private val MIGRATE_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `grade` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kcdm` TEXT NOT NULL, `kcmc` TEXT NOT NULL, " +
                        "`xnxq` TEXT NOT NULL, `xnxqmc` TEXT NOT NULL, " +
                        "`kcxz` TEXT NOT NULL, `kclb` TEXT NOT NULL, " +
                        "`xf` REAL NOT NULL, `zzcj` TEXT NOT NULL, " +
                        "`bkcx` TEXT NOT NULL, `yxmc` TEXT NOT NULL, `sffx` INTEGER NOT NULL)",
                )
            }
        }

        /** v2 → v3：course 表新增 hidden 列（教务课程隐藏而非删除）。 */
        private val MIGRATE_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `course` ADD COLUMN `hidden` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3 → v4：grade 加排名/考核方式列 + 新增 exam 表。 */
        private val MIGRATE_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `grade` ADD COLUMN `pm` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `grade` ADD COLUMN `zrs` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `grade` ADD COLUMN `khfs` TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `exam` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kcdm` TEXT NOT NULL, `kcmc` TEXT NOT NULL, " +
                        "`kslx` TEXT NOT NULL, `kssjms` TEXT NOT NULL, " +
                        "`ksrq` TEXT NOT NULL, `kssj` TEXT NOT NULL, `jssj` TEXT NOT NULL, " +
                        "`cdmc` TEXT NOT NULL, `zwh` TEXT NOT NULL, `jkjsbz` TEXT NOT NULL, " +
                        "`kkyxmc` TEXT NOT NULL, `xnxq` TEXT NOT NULL)",
                )
            }
        }

        /** v4 → v5：新增 todo 表（个人日程，原有课程/成绩/考试数据原样保留）。 */
        private val MIGRATE_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `todo` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, `note` TEXT NOT NULL DEFAULT '', " +
                        "`repeatMode` INTEGER NOT NULL DEFAULT 0, " +
                        "`weekdays` TEXT NOT NULL DEFAULT '0111110', " +
                        "`date` TEXT NOT NULL DEFAULT '', `time` TEXT NOT NULL, " +
                        "`remindMinutes` INTEGER NOT NULL DEFAULT 15, " +
                        "`colorIndex` INTEGER NOT NULL DEFAULT 0, " +
                        "`lastDoneDate` TEXT NOT NULL DEFAULT '')",
                )
            }
        }

        /** 仅迁移表结构；旧 DataStore 学期由 Repository 首次使用时幂等搬迁。 */
        val MIGRATE_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS schedule (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL, xn TEXT NOT NULL, xq TEXT NOT NULL, semesterName TEXT NOT NULL, firstMonday TEXT NOT NULL, totalWeeks INTEGER NOT NULL, weekMondays TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS schedule_state (id INTEGER PRIMARY KEY NOT NULL, activeScheduleId INTEGER NOT NULL, reminderVersion INTEGER NOT NULL, initialized INTEGER NOT NULL)")
                db.execSQL("INSERT INTO schedule VALUES (1, '默认课表', 0, '', '', '', '', 20, '')")
                db.execSQL("INSERT INTO schedule_state VALUES (1, 1, 1, 0)")
                db.execSQL("ALTER TABLE course ADD COLUMN scheduleId INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_course_scheduleId ON course(scheduleId)")
                db.execSQL("CREATE TABLE section_time_new (section INTEGER NOT NULL, startTime TEXT NOT NULL, endTime TEXT NOT NULL, scheduleId INTEGER NOT NULL, PRIMARY KEY(scheduleId, section))")
                db.execSQL("INSERT INTO section_time_new SELECT section, startTime, endTime, 1 FROM section_time")
                db.execSQL("DROP TABLE section_time")
                db.execSQL("ALTER TABLE section_time_new RENAME TO section_time")
            }
        }

        /** 历史考试均来自教务；保留 ID，避免升级改变提醒身份。 */
        val MIGRATE_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exam ADD COLUMN source INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v7 → v8：逐日放假标记按课表保存，已有课表等待重新导入补齐。 */
        val MIGRATE_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE schedule ADD COLUMN holidayDates TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATE_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS notice (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, publishedAt TEXT NOT NULL, external INTEGER NOT NULL, url TEXT NOT NULL, position INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS notice_feed (id INTEGER NOT NULL PRIMARY KEY, page INTEGER NOT NULL, nextPage INTEGER NOT NULL, hasNext INTEGER NOT NULL, total INTEGER NOT NULL, fetchedAt INTEGER NOT NULL)")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "beike_schedule.db",
                )
                    .addMigrations(MIGRATE_1_2, MIGRATE_2_3, MIGRATE_3_4, MIGRATE_4_5, MIGRATE_5_6, MIGRATE_6_7, MIGRATE_7_8, MIGRATE_8_9)
                    // 迁移失败的兜底保险丝。
                    //
                    // **只兜"找不到迁移路径"这一种情况**（Room 的 fallbackToDestructiveMigration
                    // 语义：findMigrationPath 返回 null 且 isMigrationRequired 为真时才
                    // dropAllTables 重建）。迁移 SQL 自身抛异常、或迁移后 schema 校验失败
                    // （"Migration didn't properly handle: ..."，例如字段类型不符、
                    // 历史版本写坏过表结构）都**不会**走这条兜底，仍是
                    // IllegalStateException → **启动即崩且无法自愈**，用户只能清应用数据。
                    // 历史 schema 已版本化；新增迁移需导出 schema 并验证完整迁移链，
                    // 所以这里目前是"备用保险丝"，不要把它当成万能兜底。
                    //
                    // 注意也不兜「用户数据丢失」：迁移正常时数据完整保留，此声明不会被触发。
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { instance = it }
            }
    }
}
