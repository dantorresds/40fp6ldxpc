package com.atendenteia.app

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Lê sites reais (precisa de internet). Rode com:
 *   ./gradlew :app:testDebugUnitTest -PsiteTest=true
 */
class SiteReaderTest {
    private val online = System.getProperty("siteTest") == "true"

    @Test
    fun semSitesCadastradosNaoLeNada() {
        assertTrue(SiteReader.read(emptyList()).isEmpty())
    }

    @Test
    fun siteComumPeloLeitor() {
        assumeTrue(online)
        val page = WebPageReader.read("https://example.com/")
        println("via=${page.via}\n${page.text.take(600)}")
        assertTrue(page.text.contains("Example", ignoreCase = true))
    }

    @Test
    fun detectaPaginaQueDependeDeJavascript() {
        val spa = "<html><head><script src=\"/app.js\"></script></head><body><div id=root></div></body></html>"
        assertTrue(WebPageReader.requiresJavascript(spa))
    }
}
