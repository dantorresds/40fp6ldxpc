package com.atendenteia.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Religa o atendente depois que o celular reinicia ou o app é atualizado. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AtendenteService.startIfEnabled(context)
        }
    }
}
