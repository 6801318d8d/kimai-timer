package io.github.x6801318d8d.kimaitimer

import android.webkit.CookieManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Creates the app's API token through Kimai's web interface, with the login of the app's WebView, so that nobody has
 * to create a token in Kimai and type it into the app. The same steps as Profile → API access → Create, with the
 * WebView's cookies:
 * - `/en/profile/` redirects to the logged-in user's profile (elsewhere when nobody is logged in);
 * - `/en/profile/<user>/create-access-token` gives the form, with its CSRF token (bound to the session);
 * - posting the form with the token's name saves the token and redirects to the API access page;
 * - `/en/profile/<user>/api-token` then shows the new token, once.
 *
 * Kimai wants a full login for these pages, not one restored by "remember me": right after the login in the app the
 * session has one. Blocking: call from a background thread.
 */
class WebToken(private val url: String, private val userAgent: String) {

    /** Nobody is logged in to the WebView, or only through "remember me": try again after a login. */
    class NotLoggedIn : IOException("not logged in to Kimai")

    private class Page(val code: Int, val location: String, val body: String)

    /** Creates a token called [name] for the logged-in user and returns it. */
    fun create(name: String): String {
        val profile = get("/en/profile/")
        val user = username(profile.location) ?: throw NotLoggedIn()
        val base = "/en/profile/$user"

        val form = get("$base/create-access-token")
        if (form.code in 300..399) throw NotLoggedIn()
        expect(form, 200)
        val fields = inputs(form.body)
        val csrf = fields.keys.firstOrNull { it.endsWith("[_token]") } ?: throw IOException("Kimai's token form has changed")
        val prefix = csrf.removeSuffix("[_token]")
        val body = mapOf(csrf to fields.getValue(csrf), "$prefix[name]" to name.take(NAME_LENGTH), "$prefix[expiresAt]" to "")
            .entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }

        val saved = post("$base/create-access-token", body)
        // a form with errors comes back with status 200
        if (saved.code !in 300..399) throw IOException("Kimai did not save the token (HTTP ${saved.code})")

        val page = get("$base/api-token")
        expect(page, 200)
        return token(page.body) ?: throw IOException("Kimai did not show the new token")
    }

    private fun expect(page: Page, code: Int) {
        if (page.code == 403) throw IOException("Kimai does not let this user create API tokens (HTTP 403)")
        if (page.code != code) throw IOException("HTTP ${page.code}")
    }

    private fun get(path: String) = request("GET", path, null)

    private fun post(path: String, form: String) = request("POST", path, form)

    private fun request(method: String, path: String, form: String?): Page {
        val address = url + path
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = TIMEOUT
        connection.readTimeout = TIMEOUT
        connection.setRequestProperty("Connection", "close")
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept", "text/html")
        CookieManager.getInstance().getCookie(address)?.let { connection.setRequestProperty("Cookie", it) }
        try {
            if (form != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(form.toByteArray()) }
            }
            val code = connection.responseCode
            // the session cookie may be renewed: the WebView keeps using the same session
            connection.headerFields.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }
                .flatMap { it.value }.forEach { CookieManager.getInstance().setCookie(address, it) }
            val body = (if (code < 400) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Page(code, connection.getHeaderField("Location").orEmpty(), body)
        } finally {
            connection.disconnect()
            CookieManager.getInstance().flush()
        }
    }

    companion object {
        private const val TIMEOUT = 15_000
        /** Kimai's column for the token name. */
        const val NAME_LENGTH = 50

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        /** The user in a redirect to their profile ("/en/profile/anna", maybe with scheme and host), else null. */
        fun username(location: String): String? =
            Regex("/profile/([^/?#]+)/?(?:[?#].*)?$").find(location)?.groupValues?.get(1)

        /** Name and value of every input of a page (value "" when it has none). */
        fun inputs(html: String): Map<String, String> =
            Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { tag ->
                val name = attribute(tag.value, "name") ?: return@mapNotNull null
                name to (attribute(tag.value, "value") ?: "")
            }.toMap()

        /** The new token on the API access page: `<pre ...><code>token</code></pre>`, shown only once. */
        fun token(html: String): String? =
            Regex("<pre[^>]*>\\s*<code>\\s*([^<\\s]+)\\s*</code>\\s*</pre>").find(html)?.groupValues?.get(1)?.let(::unescape)

        private fun attribute(tag: String, name: String): String? =
            Regex("\\s$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", RegexOption.IGNORE_CASE).find(tag)
                ?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }?.let(::unescape)

        private fun unescape(text: String): String = text
            .replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&#x3D;", "=")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    }
}
