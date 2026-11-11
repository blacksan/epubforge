package com.epubforge

import com.epubforge.update.Updater
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterVersionTest {
    @Test fun newerVersions() {
        assertTrue(Updater.isNewer("1.3.2", "1.3.1"))
        assertTrue(Updater.isNewer("1.10.0", "1.9.9"))
        assertTrue(Updater.isNewer("v2.0", "1.99.99"))
        assertTrue(Updater.isNewer("1.4.0", "0.0.57-dev"))
        assertTrue(Updater.isNewer("1.3.1", "1.3.1-dev"))
    }

    @Test fun notNewer() {
        assertFalse(Updater.isNewer("1.3.1", "1.3.1"))
        assertFalse(Updater.isNewer("1.3.0", "1.3.1"))
        assertFalse(Updater.isNewer("1.9.9", "1.10.0"))
        assertFalse(Updater.isNewer("1.3.1-beta", "1.3.1"))
        assertFalse(Updater.isNewer("1.3", "1.3.0"))
    }
}
