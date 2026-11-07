package com.joe.jmlite

import android.content.Context

/**
 * The bundled 网页中英双显互译 userscript (MIT, 夜雨 / greasyfork 469073) plus its two required
 * libraries. It is injected only on demand, never on page load, and its own floating panel is hidden
 * in favour of the app's translation sheet.
 */
object TranslateAssets {
    @Volatile
    private var bundle: Bundle? = null

    private class Bundle(
        val jquery: String,
        val crypto: String,
        val script: String,
    )

    fun chunks(context: Context, highlight: Boolean): List<String> {
        val assets = bundle ?: synchronized(this) {
            bundle ?: Bundle(
                read(context, "translate/jquery.min.js"),
                read(context, "translate/crypto-js.min.js"),
                read(context, "translate/translation.user.js"),
            ).also { bundle = it }
        }
        return listOf(
            bootstrap(highlight),
            assets.jquery,
            assets.crypto,
            assets.script,
        )
    }

    private fun read(context: Context, path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun bootstrap(highlight: Boolean): String = """
        (function(){
          if(window.__jyTranslateLoaded)return;
          window.__jyTranslateLoaded=true;
          window.unsafeWindow=window;
          window.GM_addStyle=function(css){try{var s=document.createElement('style');s.textContent=css;document.documentElement.appendChild(s);}catch(e){}};
          window.GM_registerMenuCommand=function(){};
          window.GM_openInTab=function(url){try{JYTranslate.openTab(String(url));}catch(e){}};
          window.GM_setClipboard=function(text){try{JYTranslate.setClipboard(String(text));}catch(e){}};
          window.GM_getValue=function(key,fallback){
            var raw=null;try{raw=JYTranslate.getValue(String(key));}catch(e){}
            if(raw===null||raw===undefined)return Promise.resolve(fallback);
            try{return Promise.resolve(JSON.parse(raw));}catch(e){return Promise.resolve(raw);}
          };
          window.GM_setValue=function(key,value){try{JYTranslate.setValue(String(key),JSON.stringify(value));}catch(e){}};
          window.__jyGM={};
          window.GM_xmlhttpRequest=function(details){
            try{
              var id='jy'+Math.random().toString(36).slice(2);
              window.__jyGM[id]=details;
              JYTranslate.request(id,details.method||'GET',String(details.url),JSON.stringify(details.headers||{}),
                details.data===null||details.data===undefined?'':String(details.data),details.timeout||30000);
            }catch(e){
              try{if(details.onerror)details.onerror({status:0,responseText:String(e)});}catch(_){}
            }
          };
          window.__jyGMResponse=function(id,status,text,headers){
            var details=window.__jyGM[id];if(!details)return;delete window.__jyGM[id];
            var response={status:status,responseText:text,responseHeaders:headers,readyState:4,finalUrl:details.url};
            try{if(status>0){if(details.onload)details.onload(response);}else{if(details.onerror)details.onerror(response);}}catch(e){}
          };
          try{localStorage.removeItem('scrollTranslate');}catch(e){}
          window.GM_setValue('isDoubleShow',false);
          window.GM_setValue('isHighlight',${if (highlight) "true" else "false"});
          window.GM_setValue('englishAutoTranslate',false);
          window.GM_setValue('leftSelectMode',false);
          window.GM_addStyle('.translate-main{display:none!important}');
          window.__jyDouble=false;
          window.__jyHighlight=${if (highlight) "true" else "false"};
          window.__jySelect=false;
          window.__jyAuto=false;
          window.__jyMode='off';
          window.__jyClick=function(id){var e=document.getElementById(id);if(e){e.click();return true;}return false;};
          window.__jyWhenReady=function(fn){
            if(document.getElementById('en2zh')){fn();return;}
            var n=0;var check=function(){if(document.getElementById('en2zh')){fn();return;}if(n++<80)setTimeout(check,100);};setTimeout(check,80);
          };
          window.__jyRestore=function(){
            document.querySelectorAll('.translate-span').forEach(function(n){n.remove();});
            document.querySelectorAll('.translate-src').forEach(function(n){var t=document.createTextNode(n.textContent);n.replaceWith(t);});
          };
          window.__jySetDouble=function(value){if(window.__jyDouble!==value){window.__jyClick('doubleShow');window.__jyDouble=value;}};
          window.__jySetHighlight=function(value){
            if(window.__jyHighlight!==value){window.__jyClick('highlightTranslateText');window.__jyHighlight=value;}
            document.querySelectorAll('.translate-span').forEach(function(n){if(value)n.classList.add('light-color');else n.classList.remove('light-color');});
          };
          window.__jySetSelect=function(value){
            if(value){window.__jySetAuto(false);}
            if(window.__jySelect!==value){window.__jyClick('leftSelectMode');window.__jySelect=value;}
          };
          window.__jyAutoBound=false;
          window.__jySetAuto=function(value){
            if(value){window.__jySetSelect(false);}
            window.__jyAuto=!!value;
            if(value&&!window.__jyAutoBound){
              window.__jyAutoBound=true;
              window.addEventListener('scroll',function(){
                if(!window.__jyAuto)return;
                clearTimeout(window.__jyAutoTimer);
                window.__jyAutoTimer=setTimeout(function(){window.__jyApply(window.__jyMode);},500);
              });
            }
          };
          window.__jyApply=function(mode){
            window.__jyWhenReady(function(){
              window.__jyMode=mode;
              if(mode==='off'){window.__jyRestore();window.__jySetSelect(false);window.__jySetAuto(false);return;}
              window.__jySetHighlight(window.__jyHighlight);
              if(mode==='zh'){window.__jySetDouble(false);window.__jyClick('en2zh');}
              else if(mode==='en'){window.__jySetDouble(false);window.__jyClick('zh2en');}
              else if(mode==='both'){window.__jySetDouble(true);window.__jyClick('en2zh');}
            });
          };
        })();
    """.trimIndent()
}
