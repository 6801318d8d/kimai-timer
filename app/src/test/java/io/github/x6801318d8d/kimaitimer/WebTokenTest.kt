package io.github.x6801318d8d.kimaitimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The parts of Kimai's pages [WebToken] reads (Kimai 2.71, templates user/access-token and user/api-token). */
class WebTokenTest {

    @Test
    fun usernameFromTheProfileRedirect() {
        assertEquals("susan", WebToken.username("/en/profile/susan"))
        assertEquals("anna.b", WebToken.username("https://kimai.example.com/en/profile/anna.b"))
        assertEquals("anna%40example.com", WebToken.username("/it/profile/anna%40example.com/"))
        // not logged in: the redirect goes to the login page, or there is none
        assertNull(WebToken.username("/en/login"))
        assertNull(WebToken.username(""))
    }

    @Test
    fun formFields() {
        val html = """
            <form name="access_token_form" method="post" action="/en/profile/susan/create-access-token">
            <input type="text" id="access_token_form_name" name="access_token_form[name]" required="required" maxlength="50" class="form-control" />
            <input type="text" id="access_token_form_expiresAt" name="access_token_form[expiresAt]" class="form-control" autocomplete="off" />
            <input type="hidden" id="access_token_form__token" name="access_token_form[_token]" value="a1b2.C3d4-e5_F6&amp;x" data-controller="csrf-protection" />
            <INPUT value='7' name='other'>
            </form>
        """.trimIndent()
        val fields = WebToken.inputs(html)
        assertEquals("a1b2.C3d4-e5_F6&x", fields["access_token_form[_token]"])
        assertEquals("", fields["access_token_form[name]"])
        assertEquals("7", fields["other"])
        assertEquals(4, fields.size)
    }

    @Test
    fun newTokenOnTheApiAccessPage() {
        val html = """
            <div class="codeblock">
                <div class="codeblock-copy" style="position: absolute; left: 30px; padding-top: 5px">
                    <button class="btn btn-icon btn-dark" onclick="navigator.clipboard.writeText('6ccc2932be3a7e8fa1dd2c254');"></button>
                </div>
                <pre style="padding-left: 65px"><code>6ccc2932be3a7e8fa1dd2c254</code></pre>
            </div>
        """.trimIndent()
        assertEquals("6ccc2932be3a7e8fa1dd2c254", WebToken.token(html))
        // the page seen again: the token is shown only once
        assertNull(WebToken.token("<table><tr><td>Kimai Timer (Pixel 9)</td></tr></table>"))
    }
}
