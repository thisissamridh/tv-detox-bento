package com.ansub.tvhome;

// Script injected into web pages so the remote's arrows move a highlight between the page's own
// links and buttons, the way a TV app works. Nothing is read from or sent anywhere: it only finds
// clickable things on screen, picks the nearest one in the pressed direction, and clicks it on OK.
// The video player can also be blown up to fill the screen.
final class TvNav {
    private TvNav() {}

    // Defines window.__tv once per page. Every call from Java is prefixed with this, so pages that
    // load content later (or navigate without reloading) still have it.
    // Sites see the TV as a narrow phone-sized screen and show their phone layout (with a bottom tab
    // bar). A 1440-wide viewport gets the full desktop layout, scaled to fit the TV.
    // Sites that switch pages without reloading rewrite this tag, so a watcher puts it back.
    // While a video fills the screen the page goes back to the TV's own width, so the player's
    // full-screen size matches the screen exactly.
    static final String DESKTOP = "(function(){function fix(){var want=window.__tvvp||'width=1440',l=document.querySelectorAll('meta[name=viewport]');"
            + "if(!l.length){var m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);l=[m];}"
            + "for(var i=0;i<l.length;i++)if(l[i].content!==want)l[i].content=want;}"
            + "window.__tvfix=fix;fix();if(!window.__tvdesk&&document.head){window.__tvdesk=1;"
            + "new MutationObserver(fix).observe(document.head,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});}})();";

    static final String BOOT = "if(!window.__tv){(function(){"
            + "var st=document.createElement('style');"
            // Drawn inside the element, so rows that clip their edges can't hide it.
            // White ring with a dark ring inside, so it shows on light and dark buttons alike.
            + "st.textContent='.__tvf{outline:4px solid #fff!important;outline-offset:-4px!important;"
            + "box-shadow:inset 0 0 0 8px rgba(10,10,12,.85),0 0 0 3px rgba(255,255,255,.9)!important;border-radius:12px}"
            + ".__tvfull{position:fixed!important;left:0!important;top:0!important;width:100vw!important;"
            + "height:100vh!important;z-index:2147483647!important;background:#000!important;border:0!important;"
            + "margin:0!important;max-width:none!important;max-height:none!important;transform:none!important}"
            + ".__tvlift{transform:none!important;filter:none!important;contain:none!important;will-change:auto!important;"
            + "backdrop-filter:none!important;z-index:auto!important;overflow:visible!important}"
            // While a video fills the screen, everything else on the page is hidden.
            + "body.__tvplaying{visibility:hidden!important}body.__tvplaying .__tvfull{visibility:visible!important}';"
            + "(document.head||document.documentElement).appendChild(st);"
            + "})();}" + DESKTOP + "if(!window.__tv){(function(){"
            + "var SEL='a[href],button,input,select,textarea,summary,iframe,video,[role=button],[role=link],"
            + "[role=tab],[role=menuitem],[role=option],[onclick],[tabindex]:not([tabindex=\"-1\"])';"
            + "var cur=null,full=null;"
            + "function vis(e){var r=e.getBoundingClientRect();if(r.width<20||r.height<20)return false;"
            + "var s=getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none'&&+s.opacity>0.05;}"
            // Clickable things: real links and buttons, plus anything the page styles with a pointer
            // cursor (cards that sites wire up in script). Only the outermost one counts: a poster,
            // not the image inside it. The list is rebuilt only when the page changes.
            + "var cache=null;new MutationObserver(function(){cache=null;}).observe(document.documentElement,{childList:true,subtree:true});"
            + "function ptr(e){return e&&e.nodeType===1&&getComputedStyle(e).cursor==='pointer';}"
            + "function cands(){if(!cache){var set=new Set(document.querySelectorAll(SEL)),"
            + "all=document.body?document.body.getElementsByTagName('*'):[];"
            + "for(var i=0;i<all.length;i++){var e=all[i];if(!set.has(e)&&ptr(e)&&!ptr(e.parentElement))set.add(e);}"
            + "cache=[];set.forEach(function(e){if(e.tagName==='IFRAME'||e.tagName==='VIDEO'){cache.push(e);return;}"
            + "for(var p=e.parentElement;p;p=p.parentElement)if(set.has(p))return;cache.push(e);});}"
            + "var l=cache.filter(vis),rs=l.map(function(e){return e.getBoundingClientRect();});"
            // A caption link right under its poster (same width, just below) is the same item.
            + "return l.filter(function(e,i){var r=rs[i];for(var j=0;j<l.length;j++){var p=rs[j];"
            + "if(j!==i&&Math.abs(p.left-r.left)<10&&Math.abs(p.width-r.width)<10&&r.top>=p.bottom-4&&r.top-p.bottom<90&&p.height>r.height*1.8)return false;}"
            + "return true;});}"
            + "function desc(e){return e.tagName+' '+((e.innerText||e.getAttribute('aria-label')||e.src||e.href||'')+'').trim().slice(0,40);}"
            // Sites rebuild parts of the page (rotating banners), which throws away the highlighted
            // item. Its replacement has the same tag and text in about the same place.
            + "var last=null;"
            + "function revive(){if(cur&&document.contains(cur))return cur;if(!last)return null;var b=null,bd=1e9,l=cands();"
            + "for(var i=0;i<l.length;i++){var e=l[i];if(e.tagName!==last.t||desc(e)!==last.d)continue;var r=e.getBoundingClientRect(),"
            + "dd=Math.abs(r.left-last.x)+Math.abs(r.top-last.y);if(dd<bd){bd=dd;b=e;}}"
            + "if(b){cur=b;b.classList.add('__tvf');}return b;}"
            + "function set(e){if(cur)cur.classList.remove('__tvf');cur=e;if(!e)return;e.classList.add('__tvf');"
            + "var rr=e.getBoundingClientRect();last={t:e.tagName,d:desc(e),x:rr.left,y:rr.top};"
            + "console.log('tvnav '+desc(e));"
            + "e.scrollIntoView({block:'center',inline:'center',behavior:'smooth'});}"
            + "function pinned(e){for(var p=e;p&&p!==document.body;p=p.parentElement){var q=getComputedStyle(p).position;"
            + "if(q==='fixed'||q==='sticky')return true;}return false;}"
            + "function mid(r){return{x:r.left+r.width/2,y:r.top+r.height/2};}"
            + "function inView(e){var r=e.getBoundingClientRect();return r.bottom>0&&r.top<innerHeight;}"
            + "function move(d){revive();var list=cands();"
            // Fresh page: start on the video player if there is one, else the first thing in the
            // main content (not the logo or a pinned menu).
            + "if(!cur||!document.contains(cur)||!vis(cur)){var pl=biggestPlayer();if(pl&&inView(pl)){set(pl);return 'moved';}"
            + "var v=list.filter(function(e){return inView(e)&&!pinned(e);});"
            + "v.sort(function(p,q){var a=p.getBoundingClientRect(),b=q.getBoundingClientRect();return (a.top-b.top)||(a.left-b.left);});"
            + "set(v[0]||list.filter(inView)[0]||list[0]);return 'moved';}"
            + "var a=cur.getBoundingClientRect(),ca=mid(a),best=null,bs=1e12;"
            // Edge to edge, like TV apps: how far the next item is in the pressed direction, plus how
            // far it sits off to the side. Items directly in line win easily.
            + "for(var i=0;i<list.length;i++){var e=list[i];if(e===cur)continue;var r=e.getBoundingClientRect(),c=mid(r),m,x,h=d==='left'||d==='right';"
            + "if(d==='right'){if(c.x<=ca.x+4||r.right<=a.right)continue;m=r.left-a.right;}"
            + "else if(d==='left'){if(c.x>=ca.x-4||r.left>=a.left)continue;m=a.left-r.right;}"
            + "else if(d==='down'){if(c.y<=ca.y+4||r.bottom<=a.bottom)continue;m=r.top-a.bottom;}"
            + "else{if(c.y>=ca.y-4||r.top>=a.top)continue;m=a.top-r.bottom;}"
            + "m=Math.max(m,0);"
            + "x=h?Math.max(0,Math.max(a.top,r.top)-Math.min(a.bottom,r.bottom)):Math.max(0,Math.max(a.left,r.left)-Math.min(a.right,r.right));"
            + "var s=m+x*3+(x>0?400:0);"
            // Pinned bars (headers, bottom menus) only win when nothing else is that way.
            + "if(!h&&pinned(e)&&!pinned(cur))s+=5000;if(s<bs){bs=s;best=e;}}"
            + "if(best){set(best);return 'moved';}"
            + "if(d==='down'||d==='up'){scrollBy({top:(d==='down'?1:-1)*innerHeight*0.6,behavior:'smooth'});return 'scrolled';}"
            + "return 'edge';}"
            + "function isPlayer(e){return e&&(e.tagName==='IFRAME'||e.tagName==='VIDEO');}"
            + "function biggestPlayer(){var b=null,ba=0,l=document.querySelectorAll('iframe,video');"
            + "for(var i=0;i<l.length;i++){var r=l[i].getBoundingClientRect(),ar=r.width*r.height;if(ar>ba){ba=ar;b=l[i];}}"
            + "return ba>40000?b:null;}"
            // OK: players go full screen (Java then taps the middle to start them); fields get typing focus.
            + "function ok(){if(!revive()){move('down');return 'none';}"
            + "if(isPlayer(cur))return 'player';"
            + "if(/^(INPUT|TEXTAREA|SELECT)$/.test(cur.tagName)){cur.focus();cur.click();return 'input';}"
            // Everything else gets a real tap in its middle (Java does it), so cards that only listen
            // on an inner element still open. Returned as a share of the visible screen.
            + "var r=cur.getBoundingClientRect(),vv=window.visualViewport,ox=vv?vv.offsetLeft:0,oy=vv?vv.offsetTop:0,"
            + "vw=vv?vv.width:innerWidth,vh=vv?vv.height:innerHeight;"
            + "return 'tap:'+((r.left+r.width/2-ox)/vw)+','+((r.top+Math.min(r.height/2,r.height-4)-oy)/vh);}"
            + "function fs(on){if(on){var e=isPlayer(cur)?cur:biggestPlayer();if(!e)return false;"
            + "e.classList.remove('__tvf');e.classList.add('__tvfull');"
            + "for(var p=e.parentElement;p;p=p.parentElement)p.classList.add('__tvlift');"
            + "document.documentElement.style.overflow='hidden';document.body.classList.add('__tvplaying');"
            // Exactly the visible part of the page, whatever zoom the site ends up at.
            + "var vv=window.visualViewport,st=e.style;st.setProperty('left',(vv?vv.offsetLeft:0)+'px','important');"
            + "st.setProperty('top',(vv?vv.offsetTop:0)+'px','important');st.setProperty('width',(vv?vv.width:innerWidth)+'px','important');"
            + "st.setProperty('height',(vv?vv.height:innerHeight)+'px','important');full=e;return true;}"
            + "if(!full)return false;full.classList.remove('__tvfull');document.body.classList.remove('__tvplaying');"
            + "['left','top','width','height'].forEach(function(k){full.style.removeProperty(k);});"
            + "var l=document.querySelectorAll('.__tvlift');for(var i=0;i<l.length;i++)l[i].classList.remove('__tvlift');"
            + "document.documentElement.style.overflow='';set(full);full=null;return true;}"
            + "function clear(){if(cur)cur.classList.remove('__tvf');cur=null;}"
            + "setInterval(function(){if(cur&&!document.contains(cur))revive();},700);"
            + "window.__tv={move:move,ok:ok,fs:fs,clear:clear,isFull:function(){return !!full;}};"
            + "})();}";
}
