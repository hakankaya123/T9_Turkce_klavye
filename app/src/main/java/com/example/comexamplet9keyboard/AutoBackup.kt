package com.example.comexamplet9keyboard

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.edit
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Kullanıcı sözlüğünün her gün seçilen saatte (varsayılan 23:45) "İndirilenler" klasörüne otomatik yedeklenmesi.
 * Dosya adı sabittir; her yedek bir öncekinin ÜZERİNE yazılır (eski yedek silinip yerine yenisi geçer).
 *
 * Zamanlama AlarmManager ile yapılır (ek izin gerekmez). Android pil tasarrufu nedeniyle alarm
 * 23:45'ten birkaç dakika sonra çalışabilir. Bazı üreticiler (Honor/Huawei/Xiaomi...) arka plan
 * alarmlarını hiç çalıştırmaz; bu yüzden ikinci bir güvence vardır: klavye her açıldığında, son
 * 23:45'ten beri yedek alınmamışsa yedek hemen alınır (bkz. [runIfMissed]). Cihaz kapalıysa açılışta da
 * kaçırılan yedek alınır.
 */
object AutoBackup {
    private const val TAG = "AutoBackup"
    const val FILE_NAME = "t9-sozluk-yedek.json"
    const val ACTION_RUN = "com.example.comexamplet9keyboard.AUTO_BACKUP"
    const val DEFAULT_HOUR = 23
    const val DEFAULT_MINUTE = 45
    private const val KEY_HOUR = "hour"
    private const val KEY_MINUTE = "minute"
    private const val REQUEST_CODE = 2345

    private const val KEY_ENABLED = "enabled"
    private const val KEY_LAST_TRY = "last_try"
    private const val KEY_LAST_OK = "last_ok"
    private const val KEY_LAST_ERR = "last_err"
    private const val KEY_LAST_SRC = "last_src"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("t9_auto", Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit { putBoolean(KEY_ENABLED, on) }
        if (on) schedule(ctx) else cancel(ctx)
    }

    /** Son yedeğin durumu: "Son yedek: 12.11.2026 23:46" ya da hata metni; hiç denenmediyse null. */
    fun statusText(ctx: Context): String? {
        val p = prefs(ctx)
        val err = p.getString(KEY_LAST_ERR, null)
        if (err != null) return "Son deneme başarısız: $err"
        val ok = p.getLong(KEY_LAST_OK, 0L)
        if (ok <= 0L) return null
        val f = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("tr-TR"))
        val src = p.getString(KEY_LAST_SRC, null)
        return "Son yedek: " + f.format(Date(ok)) + (if (src != null) " ($src)" else "")
    }

    // ── Yedek saati (kullanıcı seçer) ──

    fun hour(ctx: Context) = prefs(ctx).getInt(KEY_HOUR, DEFAULT_HOUR)
    fun minute(ctx: Context) = prefs(ctx).getInt(KEY_MINUTE, DEFAULT_MINUTE)

    fun timeText(ctx: Context) = "%02d:%02d".format(hour(ctx), minute(ctx))

    /** Yedek saatini değiştirir; açıksa alarmı yeni saate göre yeniden kurar. */
    fun setTime(ctx: Context, hour: Int, minute: Int) {
        prefs(ctx).edit {
            putInt(KEY_HOUR, hour); putInt(KEY_MINUTE, minute)
            // Saat değişimi, "kaçırılmış yedek" gibi görünüp hemen yedek aldırmasın.
            putLong(KEY_LAST_TRY, System.currentTimeMillis())
        }
        if (isEnabled(ctx)) schedule(ctx)
    }

    // ── Zamanlama ──

    private fun alarmIntent(ctx: Context) =
        Intent(ctx, AutoBackupReceiver::class.java).setAction(ACTION_RUN)

    private fun pending(ctx: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(ctx, REQUEST_CODE, alarmIntent(ctx), flags or PendingIntent.FLAG_IMMUTABLE)

    private fun atTime(now: Long, hour: Int, minute: Int): Calendar = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    /** Şu andan sonraki ilk yedek saati (varsayılan 23:45). */
    internal fun nextTrigger(now: Long, hour: Int = DEFAULT_HOUR, minute: Int = DEFAULT_MINUTE): Long {
        val c = atTime(now, hour, minute)
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    /** Şu andan önceki en son yedek saati (yani en son çalışması gereken yedek zamanı). */
    internal fun lastDue(now: Long, hour: Int = DEFAULT_HOUR, minute: Int = DEFAULT_MINUTE): Long {
        val c = atTime(now, hour, minute)
        if (c.timeInMillis > now) c.add(Calendar.DAY_OF_MONTH, -1)
        return c.timeInMillis
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(ctx, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            nextTrigger(System.currentTimeMillis(), hour(ctx), minute(ctx)),
            pi
        )
    }

    private fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        pending(ctx, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
    }

    /**
     * Açıksa alarmı yeniden kurar. Aynı PendingIntent ile kurmak mevcut alarmın yerine geçer, zararsızdır.
     * (PendingIntent kaydının varlığı alarmın hâlâ kurulu olduğunu göstermez; güncelleme/üretici temizliği
     * alarmı silmiş olabilir.)
     */
    fun ensureScheduled(ctx: Context) {
        if (isEnabled(ctx)) schedule(ctx)
    }

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Güvence: son yedek saatinden beri yedek denenmediyse (alarm çalışmadıysa) arka planda yedek alır.
     * Klavye açıldığında çağrılır; ucuzdur (iki küçük ayar okuması).
     */
    fun runIfMissed(ctx: Context, source: String) {
        if (!isEnabled(ctx) || !missedSinceLastRun(ctx)) return
        if (!running.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        Thread {
            try {
                runBackup(app, source)
            } finally {
                running.set(false)
            }
        }.start()
    }

    /** Cihaz kapalıyken ya da alarm kaçtığında: son yedek saatinden beri hiç denenmediyse true. */
    fun missedSinceLastRun(ctx: Context): Boolean =
        prefs(ctx).getLong(KEY_LAST_TRY, 0L) < lastDue(System.currentTimeMillis(), hour(ctx), minute(ctx))

    // ── Yedek alma ──

    /** Yedeği yazar. Başarılıysa null, değilse hata mesajı döner. Arka plan iş parçacığında çağrılmalı. */
    @Synchronized
    fun runBackup(ctx: Context, source: String = "elle"): String? {
        val now = System.currentTimeMillis()
        prefs(ctx).edit { putLong(KEY_LAST_TRY, now); putString(KEY_LAST_SRC, source) }
        return try {
            val data = UserDataStore.readRaw(ctx)
            val empty = listOf(data.addedWords, data.learnedWords, data.wordPairs).all { it.isBlank() }
            if (empty) {
                // Veri yokken (ör. silinmiş/sıfırlanmış) iyi bir yedeğin üzerine boş dosya yazma.
                prefs(ctx).edit { putString(KEY_LAST_ERR, "Sözlük boş; mevcut yedek korundu") }
                return "Sözlük boş; mevcut yedek korundu"
            }
            val bytes = UserDataBackup.exportData(ctx).toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= 29) writeMediaStore(ctx, bytes) else writeLegacy(bytes)
            prefs(ctx).edit { putLong(KEY_LAST_OK, now); remove(KEY_LAST_ERR) }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Otomatik yedek başarısız", e)
            val msg = e.message ?: e.javaClass.simpleName
            prefs(ctx).edit { putString(KEY_LAST_ERR, msg) }
            msg
        }
    }

    // Android 10+: İndirilenler'e MediaStore ile yazılır (izin gerekmez). Aynı adlı, bu uygulamanın
    // oluşturduğu dosya varsa içeriği doğrudan değiştirilir (üzerine yazılır).
    private fun writeMediaStore(ctx: Context, bytes: ByteArray) {
        val resolver = ctx.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val existing = resolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf(FILE_NAME, Environment.DIRECTORY_DOWNLOADS + "/"),
            null
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }

        if (existing != null) {
            try {
                resolver.openOutputStream(existing, "wt")?.use { it.write(bytes) }
                    ?: throw IOException("Dosya açılamadı")
                return
            } catch (e: Exception) {
                // Dosya başka bir yükleme/uygulamaya aitse yazılamaz; aşağıda yeni dosya oluşturulur.
                Log.w(TAG, "Mevcut yedek üzerine yazılamadı, yenisi oluşturuluyor", e)
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Dosya oluşturulamadı")
        try {
            resolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
                ?: throw IOException("Dosya açılamadı")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    // Android 9 ve öncesi: WRITE_EXTERNAL_STORAGE izni gerekir (arayüz açarken ister).
    @Suppress("DEPRECATION")
    private fun writeLegacy(bytes: ByteArray) {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("İndirilenler klasörü bulunamadı")
        val tmp = File(dir, "$FILE_NAME.tmp")
        tmp.writeBytes(bytes)
        val dst = File(dir, FILE_NAME)
        if (dst.exists() && !dst.delete()) {
            tmp.delete(); throw IOException("Eski yedek silinemedi")
        }
        if (!tmp.renameTo(dst)) {
            tmp.delete(); throw IOException("Yedek dosyası oluşturulamadı")
        }
    }
}
