package app.aaps.core.keys

/**
 * Same Nightscout site, ignoring http/https, letter case, a trailing slash and anything after `?` (such as a token).
 *
 * Shared by the sync plugin and the settings screen (2026-10-30), so a Virtual-pump master refuses the same pair of
 * addresses whether they are typed in or arrive with an imported settings file.
 */
fun sameNightscoutSite(a: String, b: String): Boolean {
    fun plain(url: String) = url.trim().substringBefore('?').lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/')
    return plain(a).isNotEmpty() && plain(a) == plain(b)
}
