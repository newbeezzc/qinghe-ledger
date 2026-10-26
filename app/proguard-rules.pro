# Room entities and generated DAO implementations.
-keep class com.qinghe.ledger.data.local.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
