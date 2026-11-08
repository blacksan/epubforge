package com.epubforge.sync

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Talks to real servers. CI starts a WebDAV server (wsgidav) and a local Supabase stack and sets
 * the environment variables; without them these tests are skipped.
 */
class SyncBackendsTest {
    private val http = OkHttpClient()
    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }

    private fun doc(n: Int) = """{"version":2,"updatedAt":$n,"stories":[],"chapters":[],"history":[]}"""

    private inline fun expectConflict(block: () -> Unit) {
        try { block(); fail("Expected a conflict") } catch (_: SyncConflict) { }
    }

    @Test fun webdavRoundTripAndConflicts() = runBlocking {
        val url = env("WEBDAV_URL"); assumeTrue("WEBDAV_URL not set", url != null)
        val user = env("WEBDAV_USER") ?: ""; val pass = env("WEBDAV_PASS") ?: ""
        val base = url!!.trimEnd('/') + "/" + UUID.randomUUID().toString().take(8) + "/"
        // MKCOL the per-test base so runs don't collide.
        val dav = WebDavBackend(http, base, user, pass)
        okhttp3.Request.Builder().url(base).method("MKCOL", null).header("Authorization", okhttp3.Credentials.basic(user, pass)).build()
            .let { http.newCall(it).execute().close() }

        dav.check()
        assertNull(dav.read())
        dav.write(doc(1), null)
        val first = dav.read()!!
        assertEquals(doc(1), first.text)
        assertNotNull("server must send an ETag", first.version)
        // Creating again without a version must not clobber the existing file.
        expectConflict { dav.write(doc(9), null) }
        dav.write(doc(2), first)
        val second = dav.read()!!
        assertEquals(doc(2), second.text)
        // A device still holding the old copy must be told to re-read and merge,
        // even on servers whose ETag doesn't change for same-sized writes within a second.
        expectConflict { dav.write(doc(3), first) }
        assertEquals(doc(2), dav.read()!!.text)

        val wrong = WebDavBackend(http, base, user, pass + "x")
        try { wrong.check(); fail("bad password accepted") } catch (_: SyncAuthError) { }
    }

    @Test fun supabaseRoundTripConflictsAndIsolation() = runBlocking {
        val url = env("SUPABASE_URL"); val key = env("SUPABASE_ANON_KEY")
        assumeTrue("SUPABASE_URL not set", url != null && key != null)
        val client = SupabaseClient(http, url!!, key!!)
        fun newUser(): SupabaseSession {
            val email = "t" + UUID.randomUUID().toString().take(10) + "@example.com"
            return client.signUp(email, "pass-" + UUID.randomUUID()) ?: error("local Supabase should not require email confirmation")
        }
        val a = newUser()
        val backendA = SupabaseBackend(client) { a }
        backendA.check()
        assertNull(backendA.read())
        backendA.write(doc(1), null)
        val r1 = backendA.read()!!
        assertEquals("1", r1.version)
        assertTrue(r1.text.contains("\"updatedAt\":1"))
        expectConflict { backendA.write(doc(9), null) }
        backendA.write(doc(2), r1)
        val r2 = backendA.read()!!
        assertEquals("2", r2.version)
        expectConflict { backendA.write(doc(3), r1) }

        // Row-level security: another user sees nothing and can't overwrite A's row.
        val b = newUser()
        val backendB = SupabaseBackend(client) { b }
        assertNull(backendB.read())
        backendB.write(doc(100), null)
        assertTrue(backendA.read()!!.text.contains("\"updatedAt\":2"))

        // Password sign-in and token refresh work.
        val refreshed = client.refresh(a)
        assertEquals(a.userId, refreshed.userId)
        try { client.signIn(a.email, "wrong-password"); fail("bad password accepted") } catch (_: SyncAuthError) { }
    }

    @Test fun setupScriptMatchesDocs() {
        val docs = listOf(File("../docs/supabase.sql"), File("docs/supabase.sql")).first { it.exists() }
        assertEquals(docs.readText(), SupabaseSetup.SQL)
    }
}
