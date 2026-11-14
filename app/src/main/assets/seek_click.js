// Called only after an explicit poster-grid site pointer click. No install-time code, listeners,
// observers, animations, CSS, or recurring timers. Never changes ordinary button clicks.
(function (fx, fy, mode) {
  'use strict';
  function seekAt(win, x, y, depth) {
    if (depth > 3) return 'none';
    var doc=win.document, el=doc.elementFromPoint(x,y);
    if (!el) return 'none';
    if (el.tagName==='IFRAME') {
      try {
        if (!el.contentDocument) return 'frame';
        var fr=el.getBoundingClientRect();
        var sx=fr.width/(el.offsetWidth || (el.clientWidth+2*(el.clientLeft||0)) || fr.width);
        var sy=fr.height/(el.offsetHeight || (el.clientHeight+2*(el.clientTop||0)) || fr.height);
        if(!(sx>0 && sy>0))return 'none';
        return seekAt(el.contentWindow,((x-fr.left)/sx-(el.clientLeft||0))*el.contentWindow.innerWidth/Math.max(1,el.clientWidth),
          ((y-fr.top)/sy-(el.clientTop||0))*el.contentWindow.innerHeight/Math.max(1,el.clientHeight),depth+1);
      } catch(e) { return 'frame'; }
    }
    // Descend open shadow roots at the hit point, without traversing unrelated trees.
    for(var si=0;si<4 && el.shadowRoot && el.shadowRoot.elementFromPoint;si++){
      var inner=el.shadowRoot.elementFromPoint(x,y);if(!inner || inner===el)break;el=inner;
    }
    var track=null,node=el;
    for(var up=0;node && up<7;up++,node=node.parentElement){
      var role=(node.getAttribute('role')||'').toLowerCase();
      var name=((node.getAttribute('aria-label')||'')+' '+(node.getAttribute('data-plyr')||'')+' '+(node.getAttribute('name')||'')).toLowerCase();
      var cls=typeof node.className==='string'?node.className:'';
      if(/volume|brightness|speed|quality/.test(name+' '+cls))return 'none';
      var known=/\b(vjs-progress-holder|jw-slider-time|plyr__progress)\b/.test(cls) ||
        node.hasAttribute('data-media-time-slider') || node.tagName==='MEDIA-TIME-SLIDER';
      var labelled=(role==='slider' || (node.tagName==='INPUT' && node.type==='range')) && /seek|timeline|progress|playback position|time/.test(name);
      if(known || labelled){track=node;break;}
      // Buttons inside a control bar must not become seeks by searching too far upward.
      if(node.tagName==='BUTTON')return 'none';
    }
    if(!track)return 'none';
    var r=track.getBoundingClientRect();
    if(r.width<80 || r.width<r.height*3 || x<r.left || x>r.right || y<r.top || y>r.bottom)return 'none';
    if(mode==='probe')return 'track';
    // Associate the track with its nearest player containing exactly one video. Do not
    // seek a different player/ad merely because it exists elsewhere in the document.
    var container=track,video=null;
    for(var vi=0;container && vi<9;vi++,container=container.parentElement){
      if(container===doc.body || container===doc.documentElement)return 'none';
      var videos=container.querySelectorAll('video');
      if(videos.length>1)return 'none';
      if(videos.length===1){video=videos[0];break;}
    }
    if(!video || !isFinite(video.duration) || video.duration<=0)return 'none';
    var fraction=Math.max(0,Math.min(1,(x-r.left)/r.width));
    try{if(win.getComputedStyle(track).direction==='rtl')fraction=1-fraction;}catch(e){}
    var wanted=video.duration*fraction;
    // Respect live/DVR seekable windows and gaps; do not jump outside buffered timelines.
    if(video.seekable && video.seekable.length){
      var inside=false,nearest=0,distance=Infinity;
      for(var ri=0;ri<video.seekable.length;ri++){
        var a=video.seekable.start(ri),b=video.seekable.end(ri);
        if(wanted>=a && wanted<=b){inside=true;break;}
        var edge=wanted<a?a:b,delta=Math.abs(wanted-edge);
        if(delta<distance){distance=delta;nearest=edge;}
      }
      if(!inside)wanted=nearest;
    }
    try{
      if(Math.abs(video.currentTime-wanted)>Math.max(1,video.duration*0.001))video.currentTime=wanted;
      return Math.abs(video.currentTime-wanted)<=Math.max(1,video.duration*0.001)?'seeked':'none';
    }catch(e){return 'none';}
  }
  if(!isFinite(fx)||!isFinite(fy)||fx<0||fx>1||fy<0||fy>1)return 'none';
  try{return seekAt(window,fx*window.innerWidth,fy*window.innerHeight,0);}catch(e){return 'none';}
})
