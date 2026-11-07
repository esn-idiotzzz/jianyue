package com.joe.jmlite

/** Only explicit authentication failures qualify; a 403/timeout/empty page is not proof. */
internal object SourceLogin {
    private val required=Regex("""请(?:先)?登[录錄]|需要登[录錄]|未登[录錄]|尚未登[录錄]|登[录錄](?:后|後|才能|以)|登录已过期|登錄已過期|(?:login|log in|sign in) (?:is )?required|(?:please|must|need to) (?:log in|login|sign in)|not logged in|unauthenticated|HTTP(?: error)?[ :]+401\b""",RegexOption.IGNORE_CASE)
    fun required(error:Throwable):Boolean = generateSequence(error){it.cause}.take(12).any{required.containsMatchIn(it.message.orEmpty())}
}
