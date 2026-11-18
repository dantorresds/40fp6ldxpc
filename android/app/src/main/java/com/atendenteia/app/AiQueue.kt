package com.atendenteia.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Fila das chamadas ao Gemini: no máximo [MAX_PARALLEL] ao mesmo tempo, por
 * ordem de chegada dentro da mesma prioridade (resposta > follow-up > resumo),
 * respeitando o limite por minuto de cada chave.
 */
object AiQueue {
    const val REPLY = 0
    const val FOLLOWUP = 1
    const val BACKGROUND = 2
    private const val MAX_PARALLEL = 5

    private class Waiter(val priority: Int, val seq: Long, val gate: CompletableDeferred<Unit>)

    private val lock = Any()
    private val waiting = mutableListOf<Waiter>()
    private var running = 0
    private var seq = 0L
    private val calls = HashMap<String, ArrayDeque<Long>>()

    /** Quantas chamadas estão esperando na fila (mostrado no app). */
    val waitingCount: Int get() = synchronized(lock) { waiting.size }

    suspend fun <T> run(priority: Int, apiKey: String, rpm: Int, block: () -> T): T {
        val w = synchronized(lock) {
            if (running < MAX_PARALLEL && waiting.isEmpty()) { running++; null }
            else Waiter(priority, seq++, CompletableDeferred()).also { waiting += it }
        }
        w?.gate?.await()
        try {
            waitRate(apiKey, rpm)
            return withContext(Dispatchers.IO) { block() }
        } finally {
            synchronized(lock) {
                val next = waiting.minWithOrNull(compareBy<Waiter>({ it.priority }, { it.seq }))
                if (next != null) { waiting.remove(next); next.gate.complete(Unit) } else running--
            }
        }
    }

    private suspend fun waitRate(apiKey: String, rpm: Int) {
        if (rpm <= 0) return
        while (true) {
            val wait = synchronized(lock) {
                val now = System.currentTimeMillis()
                val q = calls.getOrPut(apiKey.takeLast(8)) { ArrayDeque() }
                while (q.isNotEmpty() && now - q.first() > 60_000) q.removeFirst()
                if (q.size < rpm) { q.addLast(now); 0L } else 60_000 - (now - q.first()) + 200
            }
            if (wait <= 0) return
            delay(wait)
        }
    }
}
