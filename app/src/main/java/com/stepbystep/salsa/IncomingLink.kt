package com.stepbystep.salsa

import java.net.URI
import java.net.URLDecoder

/** Classifies trusted links without navigating or starting an authentication request. */
internal sealed class IncomingLink {
    data class Library(val id: String?) : IncomingLink()
    data class Login(val token: String) : IncomingLink()

    companion object {
        private val linkPattern = Regex("""https://[^\s<>"']+""")
        private val tokenPattern = Regex("[A-Za-z0-9_-]{16,512}")

        fun classify(raw: String): IncomingLink? {
            return runCatching {
                    val candidate = linkPattern.find(raw)?.value?.trimEnd('.', ',', ')') ?: return null
                    val uri = URI(candidate)
                    if (uri.scheme != "https") return null
                    val path = uri.path.orEmpty()
                    val pages = uri.host == "rodriges17.github.io" && path.startsWith("/SalsaAppCloud/")
                    val authentication = uri.host == "tzaprobvauikssnpiucl.supabase.co" && path.startsWith("/auth/v1/verify")
                    if (!pages && !authentication) return null
                    val parameters = linkedMapOf<String, String>()
                    uri.rawQuery?.split('&')?.forEach { part ->
                        val name = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                        val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                        if (!parameters.containsKey(name)) parameters[name] = value
                    }
                    val token = parameters["token_hash"]
                    if (token != null) {
                        if (parameters["type"] != "magiclink" || !tokenPattern.matches(token)) return null
                        Login(token)
                    } else if (pages) Library(parameters["library"])
                    else null
            }.getOrNull()
        }
    }
}
