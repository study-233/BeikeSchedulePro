package com.caeamer.beikeschedule.import

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JwWechatLoginUrlTest {
    @Test
    fun acceptsSchoolScanLink() {
        assertTrue(isValidJwWechatLoginUrl("https://sis.ustb.edu.cn/scan/async?sid=sample123"))
    }

    @Test
    fun rejectsOtherOriginsAndPaths() {
        assertFalse(isValidJwWechatLoginUrl("http://sis.ustb.edu.cn/scan/async?sid=sample123"))
        assertFalse(isValidJwWechatLoginUrl("https://sis.ustb.edu.cn.evil.example/scan/async?sid=sample123"))
        assertFalse(isValidJwWechatLoginUrl("https://sis.ustb.edu.cn/scan/other?sid=sample123"))
        assertFalse(isValidJwWechatLoginUrl("https://user@sis.ustb.edu.cn/scan/async?sid=sample123"))
        assertFalse(isValidJwWechatLoginUrl("https://sis.ustb.edu.cn:444/scan/async?sid=sample123"))
    }

    @Test
    fun requiresOneNonblankSid() {
        assertFalse(isValidJwWechatLoginUrl("https://sis.ustb.edu.cn/scan/async"))
        assertFalse(isValidJwWechatLoginUrl("https://sis.ustb.edu.cn/scan/async?sid="))
        assertFalse(isValidJwWechatLoginUrl("https://sis.ustb.edu.cn/scan/async?sid=%20"))
        assertFalse(isValidJwWechatLoginUrl("https://sis.ustb.edu.cn/scan/async?sid=one&sid=two"))
    }
}
