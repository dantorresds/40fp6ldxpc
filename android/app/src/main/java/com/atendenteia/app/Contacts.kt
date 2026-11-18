package com.atendenteia.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/** Confere se um número está salvo na agenda do celular. */
object Contacts {
    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /**
     * [phone] com DDI (ex.: 5511999998888). Tenta com +DDI, sem DDI e sem o 9 extra
     * do celular. Sem permissão de contatos, responde true (na dúvida, não recusa).
     */
    fun isSaved(ctx: Context, phone: String): Boolean {
        if (!hasPermission(ctx)) return true
        val digits = phone.filter(Char::isDigit)
        if (digits.length < 8) return true
        val variants = buildList {
            add("+$digits")
            if (digits.startsWith("55")) {
                val national = digits.drop(2)
                add(national)
                add("0$national")
                // Celular com e sem o nono dígito (agendas antigas).
                if (national.length == 11 && national[2] == '9') add(national.substring(0, 2) + national.substring(3))
                if (national.length == 10) add(national.substring(0, 2) + "9" + national.substring(2))
            }
        }
        return variants.any { v ->
            runCatching {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(v))
                ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: false
            }.getOrDefault(false)
        }
    }
}
