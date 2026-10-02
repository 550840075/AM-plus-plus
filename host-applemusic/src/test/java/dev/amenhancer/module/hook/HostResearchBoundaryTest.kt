package dev.amenhancer.module.hook

import dev.amenhancer.host.applemusic.AppleMusicHostProfiles
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HostResearchBoundaryTest {
    @Test fun `beta evidence never enters production profiles`() {
        val fixture = javaClass.classLoader!!.getResourceAsStream("research/7.0.0-beta-1606.json")!!
            .bufferedReader().use { JSONObject(it.readText()) }
        assertFalse(fixture.getBoolean("productionEnabled"))
        assertTrue(fixture.getJSONObject("player").getBoolean("restrictedFragmentContainer"))
        assertFalse(AppleMusicHostProfiles.isProductionBuild(fixture.getString("packageName"),fixture.getString("versionName"),fixture.getLong("versionCode")))
        assertNull(AppleMusicHostProfiles.find(fixture.getString("packageName"),fixture.getString("versionName"),fixture.getLong("versionCode")))
    }
    @Test fun `old Activity and future Fragment expose independent navigation and mini regions`() {
        val fixtures = listOf(
            HostPageFamily.LEGACY_ACTIVITY to NavigationPlacement.BOTTOM,
            HostPageFamily.FRAGMENT_VIEW to NavigationPlacement.TOP,
        )
        fixtures.forEach { (family, placement) ->
            val identity = Any()
            val port = object : PlayerSurfacePort {
                override val pageFamily = family
                override val viewSessionIdentity = identity
                override fun regions() = PlayerRegions(null,placement,null,null,family==HostPageFamily.FRAGMENT_VIEW)
            }
            assertSame(identity, port.viewSessionIdentity)
            assertEquals(placement,port.regions().navigationPlacement)
            assertEquals(family==HostPageFamily.FRAGMENT_VIEW,port.regions().restrictedPlayerContainer)
            assertEquals(listOf(PlayerPane.SONG,PlayerPane.QUEUE),PlayerPane.entries)
        }
    }
}
