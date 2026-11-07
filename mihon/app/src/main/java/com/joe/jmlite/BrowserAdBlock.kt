package com.joe.jmlite

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The bundled ABP list: network filters and standard CSS hiding, scoped to WebView only. */
internal class BrowserAdBlock(text:String) {
    private data class Cosmetic(val domains:List<String>,val selector:String,val exception:Boolean)
    private data class Network(
        val pattern:Regex,val domains:List<String>,val types:Set<String>,val excludedTypes:Set<String>,
        val thirdParty:Boolean?,val exception:Boolean,val important:Boolean,
    )
    private val cosmetics=ArrayList<Cosmetic>()
    private val network=ArrayList<Network>()
    private val resourceTypes=setOf("script","image","stylesheet","xmlhttprequest","subdocument","media","font","other","document","popup")

    init {
        text.lineSequence().map{it.trim()}.filter{it.isNotEmpty()&&!it.startsWith('!')&&!it.startsWith('[')}.forEach { line ->
            val marker=when{line.contains("#@#")->"#@#";line.contains("##")->"##";else->null}
            if(marker!=null){
                val domains=line.substringBefore(marker).split(',').filter{it.isNotBlank()}
                val selector=line.substringAfter(marker)
                // Procedural/scriptlet syntax is not CSS and must never be executed as JavaScript.
                if(!selector.contains(Regex(":(?:has-text|contains|matches-css|xpath|remove)\\(|[{}]"))&&!selector.startsWith('+'))
                    cosmetics.add(Cosmetic(domains,selector,marker=="#@#"))
            }else if(!line.contains("#?#")&&!line.contains("#$#")&&!line.contains("#%#")){
                parseNetwork(line)?.let(network::add)
            }
        }
    }

    fun css(pageUrl:String):String {
        val host=pageUrl.toHttpUrlOrNull()?.host?:return ""
        val applicable=cosmetics.filter{inDomains(host,it.domains)}
        val exceptions=applicable.filter{it.exception}.map{it.selector}.toSet()
        return applicable.asSequence().filter{!it.exception&&it.selector !in exceptions}.map{it.selector}.distinct()
            .joinToString("\n"){"$it { display: none !important; }"}
    }

    fun blocks(url:String,pageUrl:String,type:String,isMainFrame:Boolean=false):Boolean {
        // Keep navigation and authentication pages accessible.
        if(isMainFrame)return false
        val target=url.toHttpUrlOrNull()?:return false
        val page=pageUrl.toHttpUrlOrNull()?:return false
        val thirdParty=site(target)!=site(page)
        val matches=network.asSequence().filter {
            inDomains(page.host,it.domains)&&(it.thirdParty==null||it.thirdParty==thirdParty)&&
                (it.types.isEmpty()||type in it.types)&&type !in it.excludedTypes&&it.pattern.containsMatchIn(url)
        }.toList()
        return matches.any{!it.exception&&it.important} || (matches.none{it.exception}&&matches.any{!it.exception})
    }

    private fun site(url:HttpUrl)=url.topPrivateDomain()?:url.host

    private fun inDomains(host:String,domains:List<String>):Boolean {
        if(domains.any{it.startsWith('~')&&domainMatches(host,it.drop(1))})return false
        val included=domains.filterNot{it.startsWith('~')}
        return included.isEmpty()||included.any{domainMatches(host,it)}
    }

    private fun domainMatches(host:String,domain:String):Boolean {
        if('*' !in domain)return host==domain||host.endsWith(".$domain")
        val pattern=domain.split('*').joinToString("[^.]*"){Regex.escape(it)}
        return Regex("(?:^|\\.)$pattern$",RegexOption.IGNORE_CASE).containsMatchIn(host)
    }

    private fun parseNetwork(line:String):Network? {
        val exception=line.startsWith("@@")
        val filter=if(exception)line.drop(2) else line
        val pattern=filter.substringBeforeLast('$',filter)
        val options=if('$' in filter)filter.substringAfterLast('$').split(',') else emptyList()
        if(pattern.isEmpty())return null
        // Unknown modifiers are skipped rather than silently broadening a rule.
        if(options.any{it.removePrefix("~") !in resourceTypes&&it !in setOf("third-party","~third-party","match-case","important")&&!it.startsWith("domain=")})return null
        val domains=options.firstOrNull{it.startsWith("domain=")}?.substringAfter('=')?.split('|').orEmpty()
        val regex=if(pattern.startsWith('/')&&pattern.endsWith('/'))pattern.drop(1).dropLast(1) else buildString {
            var value=pattern
            when {
                value.startsWith("||")->{append("^https?://(?:[^/?#]*\\.)?");value=value.drop(2)}
                value.startsWith('|')->{append('^');value=value.drop(1)}
            }
            val end=value.endsWith('|')
            if(end)value=value.dropLast(1)
            for(char in value)append(when(char){'*'->".*";'^'->"(?:[^A-Za-z0-9_.%-]|$)";else->Regex.escape(char.toString())})
            if(end)append('$')
        }
        val compiled=try{Regex(regex,if("match-case" in options)emptySet() else setOf(RegexOption.IGNORE_CASE))}catch(_:IllegalArgumentException){return null}
        return Network(compiled,domains,options.filter{it in resourceTypes}.toSet(),options.filter{it.startsWith('~')&&it.drop(1) in resourceTypes}.map{it.drop(1)}.toSet(),
            when{ "third-party" in options->true;"~third-party" in options->false;else->null},exception,"important" in options)
    }

    companion object {
        fun resourceType(url:String,headers:Map<String,String>):String {
            val normalized=headers.mapKeys{it.key.lowercase()}
            when(normalized["sec-fetch-dest"]){
                "script","image","font"->return normalized.getValue("sec-fetch-dest")
                "style"->return "stylesheet"
                "iframe","frame"->return "subdocument"
                "audio","video"->return "media"
            }
            if(normalized["x-requested-with"].equals("XMLHttpRequest",true))return "xmlhttprequest"
            val path=url.toHttpUrlOrNull()?.encodedPath.orEmpty().lowercase()
            val accept=normalized["accept"].orEmpty()
            return when {
                path.endsWith(".js")||path.endsWith(".mjs")->"script"
                path.endsWith(".css")->"stylesheet"
                accept.startsWith("image/")||Regex("\\.(?:png|jpe?g|gif|webp|svg|ico)$").containsMatchIn(path)->"image"
                Regex("\\.(?:woff2?|ttf|otf)$").containsMatchIn(path)->"font"
                accept.startsWith("video/")||accept.startsWith("audio/")||Regex("\\.(?:mp4|webm|mp3|m3u8)$").containsMatchIn(path)->"media"
                accept.contains("application/json")->"xmlhttprequest"
                accept.contains("text/html")->"subdocument"
                else->"other"
            }
        }
    }
}
