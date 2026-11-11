package com.moshe.flipmdm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Opens the app when you dial *#*#636#*#* ("MDM" on the keypad). */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
