package app.vidro.engine

import org.json.JSONArray

object Scripts {
    val video: String = """
        (function(){
          if (window.__vidroVideo) return;
          window.__vidroVideo = true;
          function abs(u){
            try { return new URL(u, location.href).href; } catch (e) { return u || ''; }
          }
          function collect(){
            var out = [];
            var nodes = document.querySelectorAll('video');
            for (var i = 0; i < nodes.length; i++) {
              var v = nodes[i];
              if (v.currentSrc) out.push(abs(v.currentSrc));
              if (v.src) out.push(abs(v.src));
              var sources = v.querySelectorAll('source');
              for (var j = 0; j < sources.length; j++) if (sources[j].src) out.push(abs(sources[j].src));
            }
            if (window.VidroJs && out.length) {
              try { VidroJs.onVideos(JSON.stringify(out)); } catch (e) {}
            }
          }
          window.__vidroCollect = collect;
          window.__vidroCss = function(chunks){
            try {
              var root = document.head || document.documentElement;
              if (!root) return;
              var old = document.querySelectorAll('style[data-vidro]');
              for (var i = 0; i < old.length; i++) old[i].parentNode.removeChild(old[i]);
              for (var j = 0; j < chunks.length; j++) {
                var s = document.createElement('style');
                s.setAttribute('data-vidro', '1');
                s.textContent = chunks[j];
                root.appendChild(s);
              }
            } catch (e) {}
          };
          document.addEventListener('play', function(e){
            var t = e.target;
            if (!t || !t.tagName || t.tagName !== 'VIDEO') return;
            collect();
            var src = t.currentSrc || t.src || '';
            if (window.VidroJs && src) {
              try { VidroJs.onPlaying(abs(src)); } catch (e) {}
            }
          }, true);
          try {
            var obs = new MutationObserver(function(){ collect(); });
            obs.observe(document.documentElement || document, {childList:true, subtree:true, attributes:true, attributeFilter:['src']});
          } catch (e) {}
          function reportNav(){
            setTimeout(collect, 350);
            if (window.VidroJs) {
              try { VidroJs.onNavigate(location.href); } catch (e) {}
            }
          }
          var push = history.pushState;
          var replace = history.replaceState;
          if (push) history.pushState = function(){ var r = push.apply(this, arguments); reportNav(); return r; };
          if (replace) history.replaceState = function(){ var r = replace.apply(this, arguments); reportNav(); return r; };
          window.addEventListener('popstate', reportNav);
          window.addEventListener('yt-navigate-finish', reportNav);
          collect();
        })();
    """.trimIndent()

    val youtube: String = """
        (function(){
          if (window.__vidroYt) return;
          window.__vidroYt = true;
          function strip(o){
            if (!o || typeof o !== 'object') return o;
            try {
              delete o.adPlacements;
              delete o.playerAds;
              delete o.adSlots;
              if (o.playerResponse && typeof o.playerResponse === 'object') {
                delete o.playerResponse.adPlacements;
                delete o.playerResponse.playerAds;
                delete o.playerResponse.adSlots;
              }
            } catch (e) {}
            return o;
          }
          var origParse = JSON.parse;
          JSON.parse = function(text, reviver){
            var result = origParse.call(this, text, reviver);
            try {
              if (result && typeof result === 'object' && (result.adPlacements || result.playerAds || result.adSlots || (result.playerResponse && result.playerResponse.adPlacements))) {
                strip(result);
              }
            } catch (e) {}
            return result;
          };
          try {
            var stored;
            Object.defineProperty(window, 'ytInitialPlayerResponse', {
              configurable: true,
              enumerable: true,
              get: function(){ return stored; },
              set: function(v){ stored = strip(v); }
            });
          } catch (e) {}
          var origFetch = window.fetch;
          if (origFetch) {
            window.fetch = function(input, init){
              var url = '';
              try { url = typeof input === 'string' ? input : (input && input.url) || ''; } catch (e) {}
              var pending = origFetch.apply(this, arguments);
              if (url.indexOf('/youtubei/v1/player') === -1 && url.indexOf('/youtubei/v1/next') === -1) return pending;
              return pending.then(function(res){
                return res.clone().text().then(function(body){
                  try {
                    var json = origParse(body);
                    strip(json);
                    return new Response(JSON.stringify(json), {status: res.status, statusText: res.statusText, headers: res.headers});
                  } catch (e) { return res; }
                });
              });
            };
          }
          var XHR = XMLHttpRequest.prototype;
          var origOpen = XHR.open;
          var origSend = XHR.send;
          XHR.open = function(method, url){
            try { this.__vidroUrl = String(url || ''); } catch (e) {}
            return origOpen.apply(this, arguments);
          };
          XHR.send = function(){
            var self = this;
            var url = self.__vidroUrl || '';
            if (url.indexOf('/youtubei/v1/player') !== -1 || url.indexOf('/youtubei/v1/next') !== -1) {
              self.addEventListener('readystatechange', function(){
                if (self.readyState !== 4) return;
                try {
                  var json = origParse(self.responseText);
                  strip(json);
                  var text = JSON.stringify(json);
                  Object.defineProperty(self, 'responseText', {configurable: true, get: function(){ return text; }});
                  Object.defineProperty(self, 'response', {configurable: true, get: function(){ return text; }});
                } catch (e) {}
              });
            }
            return origSend.apply(this, arguments);
          };
          setInterval(function(){
            try {
              var player = document.querySelector('.html5-video-player.ad-showing, .ad-showing');
              if (!player) return;
              var skip = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
              if (skip) skip.click();
              var video = player.querySelector('video') || document.querySelector('video');
              if (video && isFinite(video.duration) && video.duration > 0 && video.duration < 120) {
                video.muted = true;
                if (video.currentTime < video.duration - 0.3) video.currentTime = video.duration;
              }
            } catch (e) {}
          }, 700);
        })();
    """.trimIndent()

    const val PAUSE_MEDIA =
        "(function(){var a=document.querySelectorAll('video,audio');for(var i=0;i<a.length;i++){try{a[i].pause()}catch(e){}}})();"

    fun cosmeticCall(chunks: List<String>): String {
        val json = JSONArray(chunks).toString()
        return "window.__vidroCss&&window.__vidroCss($json);"
    }
}
