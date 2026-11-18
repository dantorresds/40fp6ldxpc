package com.atendenteia.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigDocTest {
    /** Personas fictícias de exemplo (a ida e volta é conferida sem depender de arquivos). */
    private val exemplos = mapOf(
        1 to Persona(
            1, active = true, label = "Atendente Doceria Exemplo", storeName = "Doceria Exemplo",
            instructions = "Tire dúvidas sobre os doces e mande o link do site para encomendar.",
            preferences = "Tom simpático, mensagens curtas.",
            whenToUse = "dúvidas e encomendas de doces", keywords = "#doce, encomenda",
            handoffMessage = "Vou chamar a equipe para te ajudar.",
            links = listOf(
                LinkItem("Catálogo", "https://loja.example.com/produtos"),
                LinkItem("Encomendas", "https://loja.example.com/encomendas"),
                LinkItem("Instagram", "https://instagram.com/doceriaexemplo"),
            ),
            siteSources = listOf(
                LinkItem("Pronta entrega", "https://loja.example.com/produtos"),
                LinkItem("Encomendas", "https://loja.example.com/encomendas"),
            ),
            salesMode = false, followUp = true, followFirstMinutes = 120,
            followStartHour = 9, followEndHour = 20, followSunday = false,
            role = "atendente", closeMode = "pagina", salesPageUrl = "https://loja.example.com/encomendas",
        ),
        2 to Persona(
            2, active = true, label = "Vendas do Curso Exemplo", storeName = "Curso Exemplo",
            instructions = "Apresente o curso, responda objeções e mande o link de compra.",
            preferences = "Tom descontraído.", whenToUse = "interesse no curso", keywords = "#curso",
            handoffMessage = "Já te passo para um atendente.", offers = "Bônus de boas-vindas.",
            links = listOf(LinkItem("Página de vendas", "https://curso.example.com")),
            role = "vendedor", closeMode = "pagina", salesPageUrl = "https://curso.example.com",
        ),
        3 to Persona(
            3, active = true, label = "Suporte Exemplo", storeName = "Exemplo",
            instructions = "Ajude com problemas comuns, passo a passo.", preferences = "Tom claro e paciente.",
            whenToUse = "suporte técnico", keywords = "suporte",
            handoffMessage = "Vou acionar o suporte humano.", role = "suporte", closeMode = "pagina",
        ),
    )

    private fun md(n: Int) = ConfigDoc.renderPersona(exemplos.getValue(n))

    private fun base(n: Int) = Persona(n, active = true)

    private fun parse(text: String, n: Int = 1) = ConfigDoc.parsePersona(text, base(n)).getOrThrow()

    /** Divide o documento em seções "## TÍTULO" -> texto. */
    private fun sections(doc: String): Map<String, String> =
        Regex("(?m)^## (.+)$").findAll(doc).toList().let { ms ->
            ms.mapIndexed { i, m ->
                m.groupValues[1] to doc.substring(m.range.last + 1, if (i + 1 < ms.size) ms[i + 1].range.first else doc.length).trim()
            }.toMap()
        }

    @Test fun idaEVoltaDasPersonasDeExemplo() {
        for (n in exemplos.keys) {
            val text = md(n)
            val r = parse(text, n)
            assertEquals("avisos da persona $n: ${r.warnings}", emptyList<String>(), r.warnings)
            assertEquals("ida e volta da persona $n", text, ConfigDoc.renderPersona(r.persona))
        }
    }

    @Test fun camposDaPersona1() {
        val p = parse(md(1)).persona
        assertEquals("Atendente Doceria Exemplo", p.label)
        assertEquals("atendente", p.role)
        assertEquals("pagina", p.closeMode)
        assertEquals(3, p.links.size)
        assertEquals(2, p.siteSources.size)
        assertEquals("Pronta entrega", p.siteSources[0].title)
        assertTrue(p.photos.isEmpty())
        assertTrue(p.followUp); assertEquals(120, p.followFirstMinutes)
        assertEquals(9, p.followStartHour); assertEquals(20, p.followEndHour); assertFalse(p.followSunday)
    }

    @Test fun orquestradorIdaEVolta() {
        val personas = exemplos.values.toList()
        val base = "Você é o orquestrador.\n\n## COMO ESCOLHER NO INÍCIO DA CONVERSA\n1. Pelo código.\n\n## TROCA NO MEIO DA CONVERSA\nNunca trocar.\n"
        val text = ConfigDoc.renderOrchestrator(OrchestratorConfig(masterPrompt = base), personas)
        val rules = ConfigDoc.parseOrchestrator(text).getOrThrow()
        assertFalse(rules.contains("## PERSONAS"))
        assertEquals(text, ConfigDoc.renderOrchestrator(OrchestratorConfig(masterPrompt = rules), personas))
    }

    @Test fun editarUmCampoMudaSoEssaSecao() {
        val p = parse(md(2), 2).persona
        val before = ConfigDoc.renderPersona(p)
        val after = ConfigDoc.renderPersona(p.copy(offers = "10% na primeira compra"))
        val a = sections(before); val b = sections(after)
        assertEquals(a.keys, b.keys)
        val changed = a.keys.filter { a[it] != b[it] }
        assertEquals(listOf("OFERTAS PERMITIDAS"), changed)
        assertEquals("10% na primeira compra", b["OFERTAS PERMITIDAS"])
    }

    @Test fun camposVaziosViramTraco() {
        val doc = ConfigDoc.renderPersona(Persona(2))
        val s = sections(doc)
        assertEquals("-", s["OFERTAS PERMITIDAS"]); assertEquals("-", s["LINKS"])
        assertEquals("-", s["SITES PARA LER"]); assertEquals("-", s["FOTOS"])
        assertTrue(doc.endsWith("\n") && !doc.endsWith("\n\n"))
        assertTrue(doc.startsWith("ATENDENTE-IA-CONFIG v1 · PERSONA 2\n\n## NOME DA PERSONA\nPersona 2"))
        val back = parse(doc, 2).persona
        assertEquals("", back.offers); assertEquals(emptyList<LinkItem>(), back.links)
    }

    @Test fun secaoDesconhecidaVaiParaAsInstrucoes() {
        val doc = "ATENDENTE-IA-CONFIG v1 · PERSONA 1\n\n## PAPEL\nvendedor\n\n## INSTRUÇÕES\nVenda bem.\n\n## GARANTIA\n30 dias.\n\n## OFERTAS\nnenhuma\n"
        val r = parse(doc)
        assertEquals("vendedor", r.persona.role)
        assertEquals("Venda bem.\n\nGARANTIA\n30 dias.", r.persona.instructions)
        assertEquals("nenhuma", r.persona.offers)
        assertTrue(r.warnings.any { it.contains("GARANTIA") })
    }

    @Test fun titulosTolerantes() {
        val doc = "ATENDENTE-IA-CONFIG v1 · PERSONA 1\n\n# Nome\nLoja da Ana\n\n## tom de voz\nSimpático\n\n## Códigos\n#ana, ana\n"
        val r = parse(doc)
        assertEquals("Loja da Ana", r.persona.label)
        assertEquals("Simpático", r.persona.preferences)
        assertEquals("#ana, ana", r.persona.keywords)
    }

    @Test fun tituloSemJogoDaVelhaAceitoQuandoEhExatoEmMaiusculas() {
        // Como sai de um PDF: "##" vira só negrito, mais o título grande com o nome da persona.
        val pdfText = ConfigDoc.renderPersona(parse(md(1)).persona)
            .replace(Regex("(?m)^## "), "")
            .replaceFirst("\n\n", "\n\nAtendente Doceria Exemplo\n\n")
        val r = parse(pdfText)
        assertEquals(ConfigDoc.renderPersona(parse(md(1)).persona), ConfigDoc.renderPersona(r.persona))
    }

    @Test fun numeroDaPersonaDiferenteDaUmAviso() {
        val r = ConfigDoc.parsePersona(md(2), base(1)).getOrThrow()
        assertTrue(r.warnings.any { it.contains("Persona 2") })
    }

    @Test fun semSecaoConhecidaFalha() {
        assertTrue(ConfigDoc.parsePersona("Olá, isto é só um texto solto.\nSem seções.", base(1)).isFailure)
    }

    @Test fun papelInvalidoMantemOAnterior() {
        val r = ConfigDoc.parsePersona("## PAPEL\npirata\n\n## NOME DA PERSONA\nX\n", Persona(1, role = "suporte")).getOrThrow()
        assertEquals("suporte", r.persona.role)
        assertTrue(r.warnings.any { it.contains("pirata") })
    }

    @Test fun linksNosTresFormatos() {
        val doc = "## LINKS\nCatálogo - https://a.com/x\nInstagram: https://instagram.com/loja\nhttps://b.com\n- Mapa - https://maps.com/p?q=1\n[Site](https://s.com)\n"
        val l = parse(doc).persona.links
        assertEquals(
            listOf(LinkItem("Catálogo", "https://a.com/x"), LinkItem("Instagram", "https://instagram.com/loja"),
                LinkItem("https://b.com", "https://b.com"), LinkItem("Mapa", "https://maps.com/p?q=1"), LinkItem("Site", "https://s.com")), l)
        // e volta: título igual à url sai só como url
        val again = parse(ConfigDoc.renderPersona(parse(doc).persona)).persona.links
        assertEquals(l, again)
    }

    @Test fun fotosComELinkSemLink() {
        val doc = "## FOTOS\nbolo1 - Bolo de cenoura - quando pedirem bolo - https://x.com/a.jpg\nlocal1 - Foto só do celular\n"
        val old = listOf(PhotoItem("local1", "Foto só do celular", "arq.jpg"))
        val p = ConfigDoc.parsePersona(doc, Persona(1, photos = old)).getOrThrow().persona
        assertEquals(PhotoItem("bolo1", "Bolo de cenoura - quando pedirem bolo", "", "https://x.com/a.jpg"), p.photos[0])
        assertEquals(PhotoItem("local1", "Foto só do celular", "arq.jpg", ""), p.photos[1])
        val text = ConfigDoc.renderPersona(p)
        assertTrue(text.contains("bolo1 - Bolo de cenoura - quando pedirem bolo - https://x.com/a.jpg"))
        assertTrue(text.contains("local1 - Foto só do celular\n"))
    }

    @Test fun fotoMesmoIdComLinkNovoPedeNovoDownload() {
        val old = listOf(PhotoItem("a", "desc", "arq.jpg", "https://x.com/velha.jpg"))
        val p = ConfigDoc.parsePersona("## FOTOS\na - desc - https://x.com/nova.jpg\n", Persona(1, photos = old)).getOrThrow().persona
        assertEquals("", p.photos[0].file)
        assertEquals("https://x.com/nova.jpg", p.photos[0].url)
    }

    @Test fun retomadaSimENao() {
        val sim = parse("## RETOMADA (FOLLOW-UP)\nsim; 45 minutos; das 10 às 18; domingo sim\n").persona
        assertTrue(sim.followUp); assertEquals(45, sim.followFirstMinutes)
        assertEquals(10, sim.followStartHour); assertEquals(18, sim.followEndHour); assertTrue(sim.followSunday)
        val nao = ConfigDoc.parsePersona("## RETOMADA\nnão\n", Persona(1, followFirstMinutes = 77)).getOrThrow().persona
        assertFalse(nao.followUp); assertEquals(77, nao.followFirstMinutes)
        val horas = parse("## RETOMADA (FOLLOW-UP)\nSim, 2 horas depois, das 8h às 21h, domingo não\n").persona
        assertTrue(horas.followUp); assertEquals(120, horas.followFirstMinutes); assertEquals(8, horas.followStartHour)
        assertEquals(21, horas.followEndHour); assertFalse(horas.followSunday)
        assertEquals("não", ConfigDoc.followLine(Persona(1, followUp = false)))
    }

    @Test fun vendedorLigaModoVendas() {
        val p = ConfigDoc.parsePersona("## PAPEL\nvendedor\n", Persona(1, salesMode = false)).getOrThrow().persona
        assertTrue(p.salesMode)
    }

    @Test fun tituloMarkdownDentroDasInstrucoesNaoViraSecao() {
        val p = Persona(1, instructions = "Linha 1\n## Subtítulo meu\nLinha 2")
        val back = parse(ConfigDoc.renderPersona(p)).persona
        assertEquals("Linha 1\n### Subtítulo meu\nLinha 2", back.instructions)
    }

    @Test fun orquestradorRemovePersonasAntigaEGeraANova() {
        val velho = "Você é o orquestrador.\n\nPERSONAS\n- Persona 1 (Antiga), papel Atendente: velha\n- Persona 2 (Outra), papel Vendedor: x\n\n" +
            "COMO ESCOLHER NO INÍCIO DA CONVERSA\n1. Pelo código.\n\nTROCA NO MEIO DA CONVERSA\nNunca trocar.\n"
        val rules = ConfigDoc.orchestratorRules(velho)
        assertFalse(rules.contains("Antiga"))
        assertTrue(rules.contains("## COMO ESCOLHER NO INÍCIO DA CONVERSA"))
        assertTrue(rules.contains("## TROCA NO MEIO DA CONVERSA"))
        assertTrue(rules.startsWith("Você é o orquestrador."))

        val personas = listOf(
            Persona(1, label = "Doces", role = "atendente", whenToUse = "quer comprar doces", keywords = "#doce, bolo"),
            Persona(2, label = "Sem uso", active = false),
            Persona(3, label = "Suporte", role = "suporte", active = true),
        )
        val doc = ConfigDoc.renderOrchestrator(OrchestratorConfig(masterPrompt = velho), personas)
        assertTrue(doc.startsWith("ATENDENTE-IA-CONFIG v1 · ORQUESTRADOR\n\n## PERSONAS\n"))
        assertTrue(doc.contains("Persona 1 - Doces - atendente - quer comprar doces - códigos: #doce, bolo\n"))
        assertTrue(doc.contains("Persona 3 - Suporte - suporte\n"))
        assertFalse(doc.contains("Antiga")); assertFalse(doc.contains("Sem uso"))
        assertEquals(1, Regex("PERSONAS").findAll(doc).count())
        // mesmo documento "colado" de volta: só as regras ficam
        assertEquals(rules, ConfigDoc.parseOrchestrator(doc).getOrThrow())
        // para decidir: PERSONAS gerada + regras
        val prompt = ConfigDoc.orchestratorPrompt(OrchestratorConfig(masterPrompt = velho), personas.filter { it.active })
        assertTrue(prompt.startsWith("## PERSONAS\nPersona 1 - Doces"))
        assertTrue(prompt.contains("Nunca trocar."))
    }

    @Test fun orquestradorSemRegrasUsaAsPadrao() {
        val prompt = ConfigDoc.orchestratorPrompt(OrchestratorConfig(), listOf(Persona(1), Persona(2, active = true)))
        assertTrue(prompt.contains("COMO ESCOLHER NO INÍCIO DA CONVERSA"))
        assertTrue(prompt.contains("Persona 2 - Persona 2"))
    }

    @Test fun orquestradorSemSecoesFalha() {
        assertTrue(ConfigDoc.parseOrchestrator("qualquer coisa solta").isFailure)
    }
}
