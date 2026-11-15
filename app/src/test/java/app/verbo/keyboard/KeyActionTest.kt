package app.verbo.keyboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Held-key actions per app, the Google sign-in challenge, and syncing the key map. */
@RunWith(RobolectricTestRunner::class)
class KeyActionTest {
    private val skills = listOf(Skill("✨", "Polish", "Fix it", "edit", "p"), Skill("💬", "Reply", "Reply to it", "write", "r"))
    private fun entry(key: String, app: String, action: String) = JSONObject().put("key", key).put("app", app).put("action", action)

    @Test
    fun appKeysWinOverAllAppsAndSkillKeys() {
        val map = KeyAction.parse(JSONArray().put(entry("r", "com.whatsapp", "act:Reply options")).put(entry("b", "", "tool:translate")))
        assertEquals("act:Reply options", KeyAction.resolve(map, skills, 'r', "com.whatsapp"))
        assertEquals("skill:Reply", KeyAction.resolve(map, skills, 'r', "com.android.chrome"))
        assertEquals("tool:translate", KeyAction.resolve(map, skills, 'B', "com.whatsapp"))
        assertNull(KeyAction.resolve(map, skills, 'z', "com.whatsapp"))
        assertEquals(setOf('b', 'p', 'r'), KeyAction.bound(map, skills, "com.android.chrome"))
    }

    @Test
    fun badEntriesAreDropped() {
        val map = KeyAction.parse(JSONArray()
            .put(entry("ab", "", "tool:ai")) // two letters
            .put(entry("c", "", "tool:format")) // unknown tool
            .put(entry("d", "bad app!", "text:hi")) // not a package
            .put(entry("e", "", "run:rm -rf")) // unknown kind
            .put(entry("f", "", "text:On my way!"))
            .put(entry("f", "", "text:twice"))) // same key and app again
        assertEquals(listOf(KeyAction('f', "", "text:On my way!")), map)
    }

    @Test
    fun keymapAndPauseSyncFromTheDashboard() {
        val store = Store(RuntimeEnvironment.getApplication())
        store.applyRemote(JSONObject().put("keymap", JSONArray().put(entry("q", "com.android.chrome", "text:Thanks!")))
            .put("keyboard", JSONObject().put("keysOn", false)))
        assertEquals("text:Thanks!", KeyAction.resolve(store.keymap, emptyList(), 'q', "com.android.chrome"))
        assertFalse(store.keysOn)
        assertTrue("keymap" in Store.SYNCED.values && "keyboard.keysOn" in Store.SYNCED.values)
        assertEquals(JSONArray().put(entry("q", "com.android.chrome", "text:Thanks!")).toString(), store.fieldValue("keymap").toString())
    }

    @Test
    fun googleSignInSendsOnlyTheHashOfTheVerifier() {
        val store = Store(RuntimeEnvironment.getApplication())
        val url = Sync.googleSignInUrl(store)
        assertTrue(url.startsWith(Store.DEFAULT_SERVER + "/api/auth/google?app=1&challenge="))
        val challenge = url.substringAfter("challenge=")
        assertEquals(43, challenge.length)
        assertEquals(Sync.challenge(store.authVerifier), challenge)
        assertFalse(url.contains(store.authVerifier))
        // RFC 7636 example: the verifier's SHA-256, base64url without padding.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Sync.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
}
