package com.epubforge

import com.epubforge.engine.ErrorText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorTextTest {
    @Test fun httpBlock() {
        val raw = "Error: Error: Fetch of URL 'https://archiveofourown.org/works/10057010' failed with network error Fetch of URL 'https://archiveofourown.org/works/10057010' failed with network error 418..\n    at FetchErrorHandler.onFetchError (https://appassets.androidplatform.net/assets/w2e/js/HttpClient.js:28:31)"
        val f = ErrorText.friendly(raw)
        println(f)
        assertTrue(f.startsWith("archiveofourown.org refused the request (HTTP 418)"))
    }
    @Test fun plain() {
        assertEquals("No chapters were found at this link.", ErrorText.friendly("No chapters were found at this link."))
        assertEquals("Boom", ErrorText.friendly("Error: Boom\n    at x (y.js:1:1)"))
    }
}
