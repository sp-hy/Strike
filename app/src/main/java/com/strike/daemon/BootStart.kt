package com.strike.daemon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.strike.MainActivity

/** Bring Strike up after reboot so shell attach can restore the recorder. */
class BootStart : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
