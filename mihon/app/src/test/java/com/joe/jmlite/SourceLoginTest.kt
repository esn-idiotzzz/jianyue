package com.joe.jmlite

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class SourceLoginTest {
    @Test fun explicitChineseLoginErrorsQualify(){assertTrue(SourceLogin.required(IOException("请先登录账号")));assertTrue(SourceLogin.required(IOException("請先登錄後查看")))}
    @Test fun explicitEnglishLoginErrorsQualify(){assertTrue(SourceLogin.required(IOException("Please log in via WebView")));assertTrue(SourceLogin.required(IOException("Login required")))}
    @Test fun unauthorizedStatusQualifies(){assertTrue(SourceLogin.required(IOException("HTTP error 401")))}
    @Test fun wrappedAuthenticationFailureQualifies(){assertTrue(SourceLogin.required(IOException("无法浏览",IOException("需要登录"))))}
    @Test fun ordinaryFailuresDoNotClaimLoginIsRequired(){listOf("HTTP error 403","HTTP error 429","HTTP error 500","timeout","没有漫画","Cloudflare challenge","Unable to resolve host").forEach{assertFalse(SourceLogin.required(IOException(it)),it)}}
}
