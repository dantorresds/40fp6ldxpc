package com.atendenteia.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerTest {
    private val keys = "atendimento, quero comprar"

    @Test fun aceitaVariacoes() {
        for (t in listOf("Atendimento", "ATENDIMENTO!!", "oi, atendimento por favor", "atendimneto", "atendimeto",
                "atêndimento", "Quero comprar", "quero compra")) {
            assertTrue(t, Trigger.matches(t, keys))
        }
    }

    @Test fun ignoraConversaComum() {
        for (t in listOf("oi tudo bem?", "vamos almoçar amanhã", "atenção", "comprei pão", "quero café")) {
            assertFalse(t, Trigger.matches(t, keys))
        }
    }
}
