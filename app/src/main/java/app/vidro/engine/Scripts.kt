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
          var heightRe = new RegExp('(^|[^0-9])(144|240|360|480|720|1080|1440|2160)p?([^0-9]|' + String.fromCharCode(36) + ')', 'i');
          function push(out, u, label){
            if (!u || u.indexOf('blob:') === 0 || u.indexOf('data:') === 0) return;
            var text = (label || '') + ' ' + u;
            var m = text.match(heightRe);
            var height = m ? parseInt(m[2], 10) : 0;
            out.push({u: abs(u), l: label || (height ? (height + 'p') : ''), h: height});
          }
          function arm(v){
            if (!v || v.__vidroHold) return;
            v.__vidroHold = true;
            var timer = null;
            function fire(){
              var src = v.currentSrc || v.src || '';
              if (window.VidroJs && src) {
                try { VidroJs.onLongPress(abs(src)); } catch (e) {}
              }
            }
            v.addEventListener('touchstart', function(e){
              if (!e.touches || e.touches.length !== 1) return;
              clearTimeout(timer);
              timer = setTimeout(fire, 500);
            }, {passive: true});
            v.addEventListener('touchmove', function(){ clearTimeout(timer); }, {passive: true});
            v.addEventListener('touchend', function(){ clearTimeout(timer); }, {passive: true});
            v.addEventListener('touchcancel', function(){ clearTimeout(timer); }, {passive: true});
            v.addEventListener('contextmenu', function(e){
              if (v.tagName !== 'VIDEO') return;
              e.preventDefault();
              fire();
            });
          }
          function collect(){
            var out = [];
            var nodes = document.querySelectorAll('video');
            for (var i = 0; i < nodes.length; i++) {
              var v = nodes[i];
              arm(v);
              if (v.currentSrc) push(out, v.currentSrc, v.getAttribute('title') || '');
              if (v.src) push(out, v.src, '');
              var sources = v.querySelectorAll('source');
              for (var j = 0; j < sources.length; j++) {
                var src = sources[j].src || sources[j].getAttribute('src');
                var label = sources[j].getAttribute('label') || sources[j].getAttribute('title') || sources[j].getAttribute('res') || '';
                if (src) push(out, src, label);
              }
            }
            if (window.VidroJs && out.length) {
              try { VidroJs.onVideos(JSON.stringify(out)); } catch (e) {}
            }
          }
          var collectTimer = null;
          function scheduleCollect(){
            if (collectTimer) return;
            collectTimer = setTimeout(function(){ collectTimer = null; collect(); }, 500);
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
            var obs = new MutationObserver(function(){ scheduleCollect(); });
            obs.observe(document.documentElement || document, {childList:true, subtree:true});
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
          }, 1000);
        })();
    """.trimIndent()

    const val PAUSE_MEDIA =
        "(function(){var a=document.querySelectorAll('video,audio');for(var i=0;i<a.length;i++){try{a[i].pause()}catch(e){}}})();"

    /** Reads the page video's currentTime, then pauses every media element so audio does not double. */
    fun capturePlayback(): String = """
        (function(){
          function pick(){
            var nodes = document.querySelectorAll('video');
            var playing = null;
            var playingArea = -1;
            var largest = null;
            var largestArea = -1;
            for (var i = 0; i < nodes.length; i++) {
              var v = nodes[i];
              var area = (v.videoWidth || v.clientWidth || 0) * (v.videoHeight || v.clientHeight || 0);
              if (!largest || area > largestArea) { largest = v; largestArea = area; }
              if (!v.paused && !v.ended && v.readyState > 0) {
                if (!playing || area > playingArea) { playing = v; playingArea = area; }
              }
            }
            return playing || largest || nodes[0] || null;
          }
          var v = pick();
          var ms = 0;
          var playing = 0;
          if (v) {
            ms = Math.max(0, Math.round((v.currentTime || 0) * 1000));
            playing = (!v.paused && !v.ended) ? 1 : 0;
          }
          var all = document.querySelectorAll('video,audio');
          for (var j = 0; j < all.length; j++) { try { all[j].pause(); } catch (e) {} }
          return ms + '|' + playing;
        })();
    """.trimIndent()

    fun applyPlayback(positionMs: Long, play: Boolean): String {
        val seconds = (positionMs.coerceAtLeast(0L) / 1000.0)
        val shouldPlay = if (play) "1" else "0"
        return """
            (function(){
              function pick(){
                var nodes = document.querySelectorAll('video');
                var largest = null;
                var largestArea = -1;
                var timed = null;
                for (var i = 0; i < nodes.length; i++) {
                  var v = nodes[i];
                  var area = (v.videoWidth || v.clientWidth || 0) * (v.videoHeight || v.clientHeight || 0);
                  if (!largest || area > largestArea) { largest = v; largestArea = area; }
                  if (!timed || (v.currentTime || 0) > (timed.currentTime || 0)) timed = v;
                }
                return largestArea > 0 ? largest : (timed || nodes[0] || null);
              }
              var v = pick();
              if (!v) return;
              var target = $seconds;
              function go(){
                try { v.currentTime = target; } catch (e) {}
                if ($shouldPlay === 1) {
                  var pending = v.play();
                  if (pending && pending.catch) pending.catch(function(){});
                } else {
                  try { v.pause(); } catch (e) {}
                }
              }
              if (v.readyState >= 1) go();
              else v.addEventListener('loadedmetadata', go, {once:true});
            })();
        """.trimIndent()
    }

    fun cosmeticCall(chunks: List<String>): String {
        val json = JSONArray(chunks).toString()
        return "window.__vidroCss&&window.__vidroCss($json);"
    }

    /**
     * Sets playbackRate while a finger is held. [gen] lets a late call from an
     * older hold lose to the release (or a newer hold) that already ran.
     * If the element rejects the rate, slower steps are tried, down to 1×.
     */
    fun holdRate(rate: Double, gen: Int = 0): String =
        """
        (function(){
          ${PICK_VIDEO}
          if(!v) return '1';
          var known=v.__vidroGen||0;
          if($gen>0 && known>$gen) return String(Math.round(v.playbackRate||1));
          if($gen>0 && known!==$gen){
            v.__vidroGen=$gen;
            v.__vidroRate=v.playbackRate||1;
          } else if(v.__vidroRate==null){
            v.__vidroRate=v.playbackRate||1;
          }
          try { v.preservesPitch=true; } catch(e) {}
          try { v.webkitPreservesPitch=true; } catch(e) {}
          var want=Math.round($rate);
          if(!isFinite(want) || want<1) want=2;
          var tries=[];
          for(var r=want;r>=1;r--) tries.push(r);
          var applied=null;
          for(var i=0;i<tries.length;i++){
            try {
              if(Math.abs((v.playbackRate||0)-tries[i])>0.05) v.playbackRate=tries[i];
              var got=v.playbackRate;
              if(isFinite(got) && Math.abs(got-tries[i])<0.25){ applied=got; break; }
            } catch(e) {}
          }
          if(applied==null){
            try { v.playbackRate=v.__vidroRate||1; } catch(e) {}
            applied=v.playbackRate||1;
          }
          return String(Math.round(applied));
        })();
        """.trimIndent()

    fun holdRelease(gen: Int = 0): String =
        """
        (function(){
          ${PICK_VIDEO}
          if(!v) return;
          var known=v.__vidroGen||0;
          if($gen>0 && known>$gen) return;
          if($gen>0) v.__vidroGen=$gen;
          var r=(v.__vidroRate==null)?(v.playbackRate||1):v.__vidroRate;
          v.__vidroRate=null;
          try { v.playbackRate=r; } catch(e) {}
        })();
        """.trimIndent()

    fun holdSeek(deltaSec: Double): String =
        "(function(){${PICK_VIDEO}if(!v||!isFinite(v.currentTime))return;var t=Math.max(0,v.currentTime+($deltaSec));if(isFinite(v.duration)&&v.duration>0)t=Math.min(t,Math.max(0,v.duration-0.05));try{v.currentTime=t;}catch(e){}})();"

    fun objectFit(mode: String): String {
        val fit = when (mode) {
            "FILL" -> "cover"
            "STRETCH" -> "fill"
            "ORIGINAL" -> "none"
            else -> "contain"
        }
        return """
            (function(){
              var nodes=document.querySelectorAll('video');
              for(var i=0;i<nodes.length;i++){
                nodes[i].style.setProperty('object-fit','$fit','important');
              }
            })();
        """.trimIndent()
    }

    private const val PICK_VIDEO =
        "var nodes=document.querySelectorAll('video');var best=null,area=-1;for(var i=0;i<nodes.length;i++){var n=nodes[i];var a=(n.videoWidth||n.clientWidth||0)*(n.videoHeight||n.clientHeight||0);if(!best||a>area){best=n;area=a;}}var v=best||nodes[0];"
}
