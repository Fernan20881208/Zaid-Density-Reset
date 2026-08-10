package com.zaid.densityreset.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {

    @Test
    fun stripsHtmlAndMarkdownLinksWithoutRenderingRemoteHtml() {
        val rendered = sanitizeReleaseNotes(
            """
            ## Novedades
            - **Nuevo Game Launcher**
            - [Detalles](https://example.invalid/release)
            <script>alert('x')</script>
            """.trimIndent()
        )

        assertTrue(rendered.contains("Novedades"))
        assertTrue(rendered.contains("• Nuevo Game Launcher"))
        assertTrue(rendered.contains("• Detalles"))
        assertFalse(rendered.contains("https://"))
        assertFalse(rendered.contains("<script>"))
    }
}
