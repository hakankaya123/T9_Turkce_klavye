package com.example.comexamplet9keyboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Her gün 23:45 alarmını ve cihaz açılışı / uygulama güncellemesi / saat değişimi olaylarını alır.
 * Her olayda önce bir sonraki alarm kurulur (zincir kopmasın), sonra gerekirse yedek alınır.
 */
class AutoBackupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (!AutoBackup.isEnabled(app)) return
        AutoBackup.schedule(app)
        val isAlarm = intent.action == AutoBackup.ACTION_RUN
        val runNow = isAlarm || AutoBackup.missedSinceLastRun(app)
        if (!runNow) return
        val source = if (isAlarm) "alarm" else "açılış"
        val result = goAsync()
        Thread {
            try {
                AutoBackup.runBackup(app, source)
            } finally {
                result.finish()
            }
        }.start()
    }
}
