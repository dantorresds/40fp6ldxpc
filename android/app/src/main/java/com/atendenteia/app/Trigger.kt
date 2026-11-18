package com.atendenteia.app

import java.text.Normalizer

/**
 * Palavra-chave para ativar o atendente numa conversa (uso no WhatsApp
 * pessoal). Aceita maiúsculas/minúsculas, sem acento, pontuação e pequenos
 * erros de digitação: até 1 letra errada em palavras de 4 a 7 letras e até 2
 * em palavras maiores. Palavra-chave com várias palavras ("quero comprar")
 * é procurada como sequência.
 */
object Trigger {

    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
            .lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun tolerance(len: Int) = when {
        len >= 8 -> 2
        len >= 4 -> 1
        else -> 0
    }

    /** Distância de edição (com troca de letras vizinhas contando como 1). */
    fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    private fun wordMatches(word: String, key: String) =
        word == key || (kotlin.math.abs(word.length - key.length) <= tolerance(key.length) && distance(word, key) <= tolerance(key.length))

    /** A mensagem contém alguma das palavras-chave (separadas por vírgula ou linha)? */
    fun matches(text: String, keywords: String): Boolean {
        val words = normalize(text).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val keys = keywords.split(',', ';', '\n').map { normalize(it) }.filter { it.isNotEmpty() }
        for (key in keys) {
            val parts = key.split(' ')
            if (parts.size == 1) {
                if (words.any { wordMatches(it, key) }) return true
                // Palavra-chave grudada em outra ("oiatendimento") ou com espaço a mais ("aten dimento").
                val joined = words.joinToString("")
                if (key.length >= 5 && joined.contains(key)) return true
            } else {
                for (i in 0..words.size - parts.size) {
                    if (parts.indices.all { k -> wordMatches(words[i + k], parts[k]) }) return true
                }
            }
        }
        return false
    }
}
