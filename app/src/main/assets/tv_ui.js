// Remote-driven UI only. No button/ad interception, CSS filters, or global darkening.
(function () {
  'use strict';
  if (window.__tvUi) return;
  var host=(location.hostname||'').toLowerCase().replace(/\.$/,'');
  var documentId='';try{documentId=window.Buccaneer.currentDocument();}catch(e){}
  var fieldSequence=0;
  var fields={};
  function fieldId(el){if(!el.__buccaneerField)try{Object.defineProperty(el,'__buccaneerField',{value:String(++fieldSequence)});}catch(e){return '';}fields[el.__buccaneerField]=el;return el.__buccaneerField;}
  function byId(id){return fields[id]||null;}
  var frameId=Math.random().toString(36).slice(2)+'-'+Date.now();
  var selector='[role="listbox"], [role="menu"], .dropdown-menu, [data-radix-select-content], [data-radix-dropdown-menu-content], .MuiMenu-paper, .MuiPopover-paper';
  function editable(el) {
    if (!el) return null;
    if (el.tagName==='TEXTAREA'||el.isContentEditable) return el;
    if(el.tagName==='INPUT' && /^(text|search|email|url|tel|number|password)?$/.test((el.type||'').toLowerCase())) return el;
    return null;
  }
  function active() {
    var el=document.activeElement;
    for(var i=0;el && i<8;i++) {
      if(el.shadowRoot && el.shadowRoot.activeElement) el=el.shadowRoot.activeElement;
      else break;
    }
    return editable(el);
  }
  function snapshot(el) {
    var value=el.value!==undefined?el.value:(el.textContent||''),pw=el.type==='password';
    return {ok:1,doc:documentId,frame:frameId,field:fieldId(el),focused:(typeof document.hasFocus!=='function'||document.hasFocus())?1:0,v:pw?'':String(value).slice(0,4096),pw:pw?1:0,length:String(value).length};
  }
  function reportField() {
    if(typeof document.hasFocus==='function' && !document.hasFocus())return;
    var el=active();if(!el)return;
    try {window.Buccaneer.editableFocused(frameId,JSON.stringify(snapshot(el)));} catch(e) {}
  }
  // Read only, qualified by document/frame/field. Never expose password contents.
  function fieldState(target,id,depth) {
    if((depth||0)>5)return '';
    if(target===frameId) {
      var el=active(),owns=false;
      try{owns=window.Buccaneer.activeInputFrame()===frameId;}catch(e){}
      return owns && el && (typeof document.hasFocus!=='function'||document.hasFocus()) && fieldId(el)===id ? JSON.stringify(snapshot(el)) : '';
    }
    var frames=document.getElementsByTagName('iframe');
    for(var i=0;i<frames.length;i++)try{
      if(!frames[i].contentDocument || !frames[i].contentWindow.__tvUi)continue;
      var result=frames[i].contentWindow.__tvUi.fieldState(target,id,(depth||0)+1);
      if(result)return result;
    }catch(e){}
    return '';
  }
  function perform(action,text,el) {
    if(!el||el.disabled||el.readOnly)return 'none';
    var kind=action==='insert'?'insertText':(action==='clear'?'deleteByCut':'deleteContentBackward');
    try {if(!el.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,cancelable:true,inputType:kind,data:action==='insert'?text:null})))return 'blocked';} catch(e) {}
    if(el.isContentEditable) {
      if(action==='clear') {var range=document.createRange();range.selectNodeContents(el);var selection=getSelection();selection.removeAllRanges();selection.addRange(range);}
      var accepted=document.execCommand(action==='insert'?'insertText':'delete',false,action==='insert'?text:null);
      if(!accepted)return 'blocked';
      reportField();return 'ok';
    }
    var value=el.value||'',start=el.selectionStart,end=el.selectionEnd;
    if(start==null||end==null)start=end=value.length;
    var insert=action==='insert'?text:'';
    if(action==='clear'){start=0;end=value.length;}
    if(action==='delete' && start===end && start>0){start--;if(start>0 && /[\uDC00-\uDFFF]/.test(value.charAt(start)) && /[\uD800-\uDBFF]/.test(value.charAt(start-1)))start--;}
    if(el.maxLength>=0 && action==='insert')insert=insert.slice(0,Math.max(0,el.maxLength-(value.length-(end-start))));
    var next=value.slice(0,start)+insert+value.slice(end);
    var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
    var desc=Object.getOwnPropertyDescriptor(proto,'value');
    if(desc && desc.set)desc.set.call(el,next);else el.value=next;
    try{el.setSelectionRange(start+insert.length,start+insert.length);}catch(e){}
    try{el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:kind,data:action==='insert'?insert:null}));}
    catch(e){el.dispatchEvent(new Event('input',{bubbles:true}));}
    reportField();return 'ok';
  }
  function edit(action,text,target,id,depth) {
    if(!/^(insert|delete|clear|submit)$/.test(action))return JSON.stringify({status:'unsupported'});
    if((depth||0)>5)return JSON.stringify({status:'unreachable'});
    var owns=false;try{owns=target===frameId && window.Buccaneer.activeInputFrame()===frameId;}catch(e){}
    if(owns){
      // The app records the field the user actually tapped; typing targets that
      // element by ID even when DOM focus has moved (the app keypad holds focus).
      // contentEditable still needs the live selection.
      var el=active()||byId(id);
      if(!el || !id || fieldId(el)!==id || !el.isConnected || el.disabled || el.readOnly ||
         (el.isContentEditable && el!==active()))return JSON.stringify({status:'stale'});
      // Validate before any action. Never fall back after an action might have executed.
      try{
        if(action==='submit'){
          // SUBMIT DESIGN — read before changing (this path regresses silently and often):
          // The page can ONLY ever see synthetic (isTrusted===false) Enter events; the app
          // turns the remote's Select key into these, so a page's own Enter handler can never
          // be relied on to run the search. React/SPA handlers frequently call preventDefault()
          // on the synthetic key (proceed=false) WITHOUT performing the search. Therefore the
          // form submission below is the PRIMARY, reliable action — it must NOT be gated on
          // `proceed`. Gating it on `proceed` (the old `form && proceed`) is exactly the bug
          // where "I can type but Enter doesn't search": the page eats the key, we skip the
          // form submit, and nothing happens. Form submit serializes the live DOM values,
          // which are correct because typing updates them. Only formless widgets fall back to
          // best-effort key dispatch.
          var eventOptions={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};
          var proceed=el.dispatchEvent(new KeyboardEvent('keydown',eventOptions));
          if(proceed)proceed=el.dispatchEvent(new KeyboardEvent('keypress',eventOptions));
          var form=el.form;
          if(form){
            var submitter=null;
            for(var si=0;si<form.elements.length;si++){
              var candidate=form.elements[si];
              if(!candidate.disabled && /^(BUTTON|INPUT)$/.test(candidate.tagName) && candidate.type==='submit'){submitter=candidate;break;}
            }
            if(typeof form.requestSubmit==='function'){
              if(submitter)form.requestSubmit(submitter);else form.requestSubmit();
            }
            else {
              // A temporary submit button preserves validation and submit handlers.
              var button=document.createElement('button');button.type='submit';button.hidden=true;
              form.appendChild(button);try{button.click();}finally{button.remove();}
            }
            el.dispatchEvent(new KeyboardEvent('keyup',eventOptions));
            return JSON.stringify({status:'submitted'});
          }
          // No form: best-effort key dispatch for formless search widgets (no forced navigation).
          el.dispatchEvent(new KeyboardEvent('keyup',eventOptions));
          return JSON.stringify({status:'key-dispatched'});
        }
        var result=perform(action,String(text||''),el);
        var state=snapshot(el);
        state.status=result==='ok'?'applied':result==='blocked'?'blocked':'unsupported';
        state.stillActive=el.isConnected && active()===el?1:0;
        return JSON.stringify(state);
      }catch(e){return JSON.stringify({status:'uncertain'});}
    }
    var frames=document.getElementsByTagName('iframe');
    for(var i=0;i<frames.length;i++)try{
      if(!frames[i].contentDocument || !frames[i].contentWindow.__tvUi)continue;
      var result=frames[i].contentWindow.__tvUi.edit(action,String(text||''),target,id,(depth||0)+1);
      if(result && JSON.parse(result).status!=='unreachable')return result;
    }catch(e){}
    return JSON.stringify({status:'unreachable'});
  }
  function type(action,text,target,depth) {
    if((depth||0)>5)return 'none';
    if(target===frameId){
      var el=active();
      var raw=edit(action,text,target,el?fieldId(el):'',depth);
      try{return JSON.parse(raw).status==='applied'?'ok':'none';}catch(e){return 'none';}
    }
    var frames=document.getElementsByTagName('iframe');
    for(var i=0;i<frames.length;i++)try{
      if(frames[i].contentDocument && frames[i].contentWindow.__tvUi &&
         frames[i].contentWindow.__tvUi.type(action,text,target,(depth||0)+1)==='ok')return 'ok';
    }catch(e){}
    return 'none';
  }
  var fitted=[];
  function restoreMenus() {
    for(var i=0;i<fitted.length;i++){
      var saved=fitted[i];
      // Restore only our own values; do not erase later site-authored changes.
      for(var j=0;j<saved.props.length;j++){
        var p=saved.props[j];
        if(saved.el.style.getPropertyValue(p.name)!==p.applied || saved.el.style.getPropertyPriority(p.name)!=='important')continue;
        if(p.value)saved.el.style.setProperty(p.name,p.value,p.priority);else saved.el.style.removeProperty(p.name);
      }
    }
    fitted=[];
  }
  function setFit(el,name,value){
    var saved=null;for(var i=0;i<fitted.length;i++)if(fitted[i].el===el){saved=fitted[i];break;}
    if(!saved){saved={el:el,props:[]};fitted.push(saved);}
    var prop=null;for(var j=0;j<saved.props.length;j++)if(saved.props[j].name===name){prop=saved.props[j];break;}
    if(!prop){prop={name:name,value:el.style.getPropertyValue(name),priority:el.style.getPropertyPriority(name),applied:''};saved.props.push(prop);}
    el.style.setProperty(name,value,'important');
    // Chromium canonicalizes fractional CSS values. Compare the actual stored value.
    prop.applied=el.style.getPropertyValue(name);
  }
  // Some sites use plain positioned divs with no menu role/class. Discover only
  // newly opened popups near the user's actual trigger, never all arbitrary page divs.
  var menuTrigger=null,menuBefore=[],structuralMenus=[];
  function nearbyPopups(trigger){
    var out=[],checked=0;
    for(var root=trigger && trigger.parentElement,depth=0;root && depth<3 && root!==document.body && root!==document.documentElement;root=root.parentElement,depth++){
      var nodes=Array.prototype.slice.call(root.children);
      for(var n=0;n<nodes.length && checked<64;n++){
        var el=nodes[n];checked++;
        if(el.children && n<8)for(var c=0;c<el.children.length && nodes.length<64;c++)nodes.push(el.children[c]);
        if(el===trigger || el.contains(trigger) || out.indexOf(el)>=0 || !/^(DIV|UL|OL|NAV|SECTION)$/.test(el.tagName))continue;
        var cs=getComputedStyle(el),r=el.getBoundingClientRect();
        if(!/^(absolute|fixed)$/.test(cs.position) || cs.display==='none' || cs.visibility==='hidden' ||
          r.width<60 || r.width>(window.innerWidth||1280)*.9 || r.height<20 || el.querySelector('video,iframe,canvas,input,textarea'))continue;
        if(el.querySelectorAll('button,a[href],[role="option"],[role="menuitem"],[role="menuitemradio"]').length<2)continue;
        out.push(el);
      }
    }
    return out;
  }
  function rememberMenuTrigger(target){
    menuTrigger=target && target.closest?target.closest('button,[role="button"],[aria-haspopup]'):null;
    menuBefore=nearbyPopups(menuTrigger);
  }
  function menuCandidates() {
    var menus=Array.prototype.slice.call(document.querySelectorAll(selector));
    var triggers=document.querySelectorAll('[aria-expanded="true"][aria-controls], [aria-expanded="true"][aria-owns]');
    for(var i=0;i<triggers.length && i<48;i++) {
      var ids=(triggers[i].getAttribute('aria-controls')||triggers[i].getAttribute('aria-owns')||'').split(/\s+/);
      for(var j=0;j<ids.length;j++){
        var el=document.getElementById(ids[j]);
        if(el && el!==triggers[i] && menus.indexOf(el)<0)menus.push(el);
      }
    }
    structuralMenus=structuralMenus.filter(function(el){return el.isConnected && getComputedStyle(el).display!=='none';});
    var nearby=nearbyPopups(menuTrigger);
    for(var k=0;k<nearby.length && structuralMenus.length<8;k++)
      if(menuBefore.indexOf(nearby[k])<0 && structuralMenus.indexOf(nearby[k])<0)structuralMenus.push(nearby[k]);
    for(var k=0;k<structuralMenus.length;k++)if(menus.indexOf(structuralMenus[k])<0)menus.push(structuralMenus[k]);
    return menus;
  }
  function verticalBounds(el,h) {
    var safe=Math.max(12,h*.05),top=safe,bottom=h-safe;
    for(var parent=el.parentElement,n=0;parent && n<16;parent=parent.parentElement,n++) {
      var cs=getComputedStyle(parent);
      if(/^(auto|scroll|overlay|hidden|clip)$/.test(cs.overflowY) || /(^|\s)(paint|strict|content)(\s|$)/.test(cs.contain)) {
        var r=parent.getBoundingClientRect(),scale=r.height/(parent.offsetHeight||r.height||1);
        // Client area excludes borders and scrollbars, in physical viewport pixels.
        top=Math.max(top,r.top+parent.clientTop*scale+8);
        bottom=Math.min(bottom,r.top+(parent.clientTop+parent.clientHeight)*scale-8);
      }
    }
    return {top:top,bottom:bottom};
  }
  function fitMenus() {
    // Removing our height temporarily can make the original box non-scrollable,
    // clamping scrollTop to zero. Preserve it through the whole measurement pass.
    var positions=[];
    for(var pi=0;pi<fitted.length;pi++){
      var previous=fitted[pi].el;
      if(previous.isConnected && previous.clientHeight>0)positions.push({el:previous,top:previous.scrollTop,left:previous.scrollLeft});
    }
    restoreMenus();
    var menus=menuCandidates(),changed=0,h=window.innerHeight||720;
    for(var i=0;i<menus.length && i<24;i++){
      var el=menus[i],r=el.getBoundingClientRect(),cs=getComputedStyle(el);
      if(r.width<20||r.height<10||cs.display==='none'||cs.visibility==='hidden'||el.querySelector('video,iframe,canvas'))continue;
      var wrapper=el.closest('[data-radix-popper-content-wrapper]');
      if(!wrapper){if(/^(fixed|absolute)$/.test(cs.position))wrapper=el;else if(el.parentElement && /^(fixed|absolute)$/.test(getComputedStyle(el.parentElement).position))wrapper=el.parentElement;}
      var bounds=verticalBounds(el,h);
      if(!wrapper || bounds.bottom-bounds.top<40 || (r.top>=bounds.top && r.bottom<=bounds.bottom))continue;
      if(wrapper!==el) {
        var controls=wrapper.querySelectorAll('button,input,video,iframe,canvas'),safe=true;
        for(var ci=0;ci<controls.length;ci++)if(!el.contains(controls[ci])){safe=false;break;}
        if(!safe)continue;
      }
      var scale=r.height/(el.offsetHeight||r.height),wr=wrapper.getBoundingClientRect();
      var wrapperScale=wr.height/(wrapper.offsetHeight||wr.height||1);
      // Convert physical movement back to CSS units for scaled popup wrappers.
      if(!isFinite(scale)||scale<=0||!isFinite(wrapperScale)||wrapperScale<=0)continue;
      var inset=cs.boxSizing==='border-box'?0:(parseFloat(cs.paddingTop)||0)+(parseFloat(cs.paddingBottom)||0)+(parseFloat(cs.borderTopWidth)||0)+(parseFloat(cs.borderBottomWidth)||0);
      setFit(el,'min-height','0px');
      var available=Math.max(1,(bounds.bottom-bounds.top)/scale-inset),siteMax=parseFloat(cs.maxHeight);
      setFit(el,'max-height',(isFinite(siteMax)?Math.min(siteMax,available):available)+'px');
      setFit(el,'overflow-y','auto');
      setFit(el,'overscroll-behavior-y','contain');
      r=el.getBoundingClientRect();var shift=r.top<bounds.top?bounds.top-r.top:(r.bottom>bounds.bottom?bounds.bottom-r.bottom:0);
      if(Math.abs(shift)>1)setFit(wrapper,'margin-top',((parseFloat(getComputedStyle(wrapper).marginTop)||0)+shift/wrapperScale)+'px');
      changed++;
    }
    for(var pi=0;pi<positions.length;pi++){
      var saved=positions[pi];
      if(saved.el.isConnected && saved.el.clientHeight>0){saved.el.scrollTop=saved.top;saved.el.scrollLeft=saved.left;}
    }
    return changed;
  }
  function scrollAxis(el,amount,vertical){
    if(!amount)return false;
    var root=document.scrollingElement||document.documentElement;
    while(el && el!==root && el!==document.body && el!==document.documentElement){
      var cs=getComputedStyle(el),overflow=vertical?cs.overflowY:cs.overflowX;
      if(/^(auto|scroll|overlay)$/.test(overflow) && (vertical?el.scrollHeight-el.clientHeight:el.scrollWidth-el.clientWidth)>1){
        if(vertical)el.scrollTop+=amount;else el.scrollLeft+=amount;
        return true; // Keep boundary scrolling in the menu, not the underlying page.
      }
      el=el.parentElement || (el.getRootNode && el.getRootNode().host);
    }
    return false;
  }
  function scrollAt(x,y,dx,dy){
    fitMenus();var el=document.elementFromPoint(x,y);if(!el)return 'none';
    for(var depth=0;depth<8 && el.shadowRoot && el.shadowRoot.elementFromPoint;depth++){var inner=el.shadowRoot.elementFromPoint(x,y);if(!inner || inner===el)break;el=inner;}
    if(el.tagName==='IFRAME'){
      try{var r=el.getBoundingClientRect(),sx=r.width/(el.offsetWidth||r.width),sy=r.height/(el.offsetHeight||r.height);if(sx>0 && sy>0 && el.contentWindow.__tvUi)return el.contentWindow.__tvUi.scrollAt((x-r.left)/sx-el.clientLeft,(y-r.top)/sy-el.clientTop,dx/sx,dy/sy);}catch(e){}
      return 'frame';
    }
    return scrollAxis(el,dy,true)||scrollAxis(el,dx,false)?'nested':'none';
  }
  function playback(){var nodes=document.querySelectorAll('video,audio');for(var i=0;i<nodes.length;i++)if(!nodes[i].paused && !nodes[i].ended && nodes[i].readyState>=1)return true;return false;}
  window.__tvUi={scrollAt:scrollAt,fitMenus:fitMenus,restoreMenus:restoreMenus,reportField:reportField,fieldState:fieldState,edit:edit,type:type,playback:playback};
  // Watch only a finite post-click window. No permanent DOM observer/polling.
  var menuObserver=null,menuExpiry=0,menuFitTimer=0,menuWatch=0,menuScans=0,fieldTimers=[];
  function stopMenuWatch(){
    menuWatch++;
    if(menuObserver)menuObserver.disconnect();menuObserver=null;
    clearTimeout(menuExpiry);clearTimeout(menuFitTimer);menuExpiry=menuFitTimer=0;
    for(var i=0;i<fieldTimers.length;i++)clearTimeout(fieldTimers[i]);fieldTimers=[];
  }
  function watchMenus(){
    stopMenuWatch();var watch=menuWatch;menuScans=0;
    function scan(){if(watch!==menuWatch)return;if(menuScans++<10)fitMenus();}
    function schedule(){
      if(watch!==menuWatch||menuFitTimer||menuScans>=9)return;
      menuFitTimer=setTimeout(function(){menuFitTimer=0;scan();},120);
    }
    try{
      if(typeof MutationObserver==='function' && document.documentElement){
        menuObserver=new MutationObserver(schedule);
        menuObserver.observe(document.documentElement,{childList:true,subtree:true});
      }
    }catch(e){menuObserver=null;}
    for(var i=0;i<2;i++)(function(delay){fieldTimers.push(setTimeout(function(){
      if(watch===menuWatch){reportField();scan();}
    },delay));})(i?650:150);
    menuExpiry=setTimeout(function(){if(watch!==menuWatch)return;scan();stopMenuWatch();},5000);
  }
  window.addEventListener('pagehide',function(){stopMenuWatch();restoreMenus();structuralMenus=[];menuTrigger=null;menuBefore=[];});
  window.addEventListener('resize',fitMenus);
  window.addEventListener('focus',function(){setTimeout(reportField,0);});
  document.addEventListener('keydown',function(e){if(e.key==='Escape'){stopMenuWatch();setTimeout(fitMenus,0);}},true);
  document.addEventListener('focusin',function(){setTimeout(reportField,0);},true);
  document.addEventListener('focusout',function(e){
    var el=editable(e.target);if(!el)return;
    try{window.Buccaneer.editableBlurred(frameId,documentId,fieldId(el));}catch(x){}
  },true);
  document.addEventListener('input',reportField,true);
  document.addEventListener('click',function(e){if(e.isTrusted){rememberMenuTrigger(e.target);watchMenus();}},true);
})();
