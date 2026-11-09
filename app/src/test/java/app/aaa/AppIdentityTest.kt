package app.aaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppIdentityTest {
    @Test
    fun samePackageInDifferentUsersHasDifferentKeys() {
        assertNotEquals(
            appIdentityKey("com.example.app", 0),
            appIdentityKey("com.example.app", 10)
        )
    }

    @Test
    fun samePackageAndUserHasStableKey() {
        assertEquals("com.example.app:10", appIdentityKey("com.example.app", 10))
    }
}
