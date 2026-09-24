package com.caeamer.beikeschedule.import

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JwAuthNavigationTest {
    @Test
    fun upgradesKnownRedirectWithoutChangingEncodedParameters() {
        val suffix = "://sso.ustb.edu.cn/idp/thirdAuth/microQr?auth_code=sample%2Bcode%3D&lck=a%26b&empty="
        assertEquals("https$suffix", secureSchoolAuthRedirect("http$suffix"))
    }

    @Test
    fun upgradesSuccessiveSchoolAuthRedirects() {
        listOf(
            "sso.ustb.edu.cn/idp/thirdAuth/microQr",
            "sso.ustb.edu.cn/idp/authCenter/thirdPartyAuthEngine",
            "byyt.ustb.edu.cn/oauth/login/code",
            "sis.ustb.edu.cn/connect/qrpage",
        ).forEach { path ->
            assertEquals("https://$path?state=sample", secureSchoolAuthRedirect("http://$path?state=sample"))
        }
    }

    @Test
    fun rejectsOtherOriginsAndAmbiguousUrls() {
        listOf(
            "https://sso.ustb.edu.cn/idp/thirdAuth/microQr",
            "http://sso.ustb.edu.cn.evil.example/idp/thirdAuth/microQr",
            "http://other.ustb.edu.cn/idp/thirdAuth/microQr",
            "http://evilustb.edu.cn/idp/thirdAuth/microQr",
            "http://user@sso.ustb.edu.cn/idp/thirdAuth/microQr",
            "http://sso.ustb.edu.cn:8080/idp/thirdAuth/microQr",
            "http://sso.ustb.edu.cn/idp/thirdAuth/microQr#fragment",
            "javascript:alert(1)",
            "invalid url",
        ).forEach { assertNull(it, secureSchoolAuthRedirect(it)) }
    }
}
