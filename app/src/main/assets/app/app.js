/* Batsight app: Chat's screens (see app.html) driven by real measurements through window.Bat (AppScreen.kt).
   Drawing code is Chat's, from the three design sources; marked APP where it now takes real data. */
(function(){
"use strict";
var NS="http://www.w3.org/2000/svg", $=function(i){return document.getElementById(i)};
var Bat=window.Bat||{phone:function(){return "8t"},tourSeen:function(){return false},tourDone:function(){},lab:function(){},buzz:function(){},save:function(){},focusListen:function(){},watchStart:function(){},watchKeep:function(){},stop:function(){},liveStart:function(){},hiveJoin:function(){},hiveHandshake:function(){},hiveSend:function(){}};
var PH=Bat.phone();
function nearTxt(){return PH==="8t"?"<b>30 cm</b>":"<b>50 cm</b>";}
var running=null;   // "listen" | "walk" | "watch" | null
function mic(on){$("mic").className=on?"mic on":"mic";$("miclabel").textContent=on?"Mic on":"Mic off";}

/* ================= Onboarding (Chat) ================= */
function ph(x,y,w,h,extra){return '<rect x="'+x+'" y="'+y+'" width="'+w+'" height="'+h+'" rx="9" fill="#0E1A2E" stroke="#8FE8F2" stroke-width="2" '+(extra||'')+'/>';}
function ringsA(cx,cy,r){return ['','r2','r3'].map(function(c){return '<circle class="ring '+c+'" cx="'+cx+'" cy="'+cy+'" r="'+r+'" fill="none" stroke="#8FE8F2" stroke-width="2"/>';}).join('');}
function mug(x,y){return '<rect x="'+x+'" y="'+y+'" width="30" height="34" rx="5" fill="#C9D6EE"/><path d="M'+(x+30)+' '+(y+9)+' q12 0 12 9 q0 9 -12 9" fill="none" stroke="#C9D6EE" stroke-width="5"/>';}
function svg(inner){return '<svg viewBox="0 0 300 180" role="img" aria-hidden="true">'+inner+'</svg>';}
var ART={
echo:function(){return svg(ph(30,60,34,62)+ringsA(70,91,26)+mug(220,76)+'<path d="M214 100 Q150 150 80 112" fill="none" stroke="#8FE8F2" stroke-width="2" stroke-dasharray="4 4"/><text x="150" y="168" fill="#9AAED0" font-size="11" text-anchor="middle">a short, quiet chirp goes out and comes back</text>');},
colours:function(){return svg('<rect x="24" y="40" width="54" height="54" rx="6" fill="none" stroke="#3DDC97" stroke-width="3"/><text x="51" y="73" fill="#3DDC97" font-size="13" text-anchor="middle">1.2 m</text><text x="51" y="120" fill="#3DDC97" font-size="11" text-anchor="middle">clear</text>'+
'<rect x="90" y="40" width="54" height="54" rx="6" fill="none" stroke="#7F8DA8" stroke-width="2.5" stroke-dasharray="5 4"/><text x="117" y="76" fill="#7F8DA8" font-size="24" text-anchor="middle">?</text><text x="117" y="120" fill="#7F8DA8" font-size="11" text-anchor="middle">can\'t tell</text>'+
'<rect x="156" y="40" width="54" height="54" rx="6" fill="#F2B84B" fill-opacity=".25" stroke="#F2B84B" stroke-width="2.5"/><text x="183" y="120" fill="#F2B84B" font-size="11" text-anchor="middle">moved</text>'+
'<rect x="222" y="40" width="54" height="54" rx="6" fill="none" stroke="#8FE8F2" stroke-width="2.5" stroke-dasharray="2 4"/><text x="249" y="120" fill="#8FE8F2" font-size="11" text-anchor="middle">probable</text>');},
mic:function(){return svg(ph(112,26,76,128)+'<circle cx="150" cy="44" r="5" fill="#FF5A5A"/><text x="150" y="86" fill="#E6EEFF" font-size="13" text-anchor="middle">Mic on</text><text x="150" y="108" fill="#9AAED0" font-size="10.5" text-anchor="middle">only while listening</text><g stroke="#9AAED0" stroke-width="2.5" fill="none"><path d="M226 80 q-10 0 -10 -9 q0 -9 10 -9 q2 -9 14 -9 q12 0 14 9 q10 0 10 9 q0 9 -10 9 z"/><path d="M222 48 L262 92" stroke="#FF5A5A"/></g><text x="240" y="112" fill="#9AAED0" font-size="10.5" text-anchor="middle">nothing leaves</text>');},
glass:function(){return svg(ph(30,60,34,62)+'<rect x="200" y="30" width="14" height="120" fill="#2A4A78" fill-opacity=".6" stroke="#8FE8F2" stroke-opacity=".5"/><path d="M68 91 L200 91" stroke="#8FE8F2" stroke-width="2"/><path d="M200 91 L250 40" stroke="#8FE8F2" stroke-width="2" stroke-dasharray="4 4"/><text x="258" y="130" fill="#7F8DA8" font-size="26" text-anchor="middle">?</text><text x="150" y="168" fill="#9AAED0" font-size="11" text-anchor="middle">smooth glass sends the sound away</text>');},
focus:function(){return svg(ph(40,30,50,100)+'<rect x="52" y="44" width="26" height="26" fill="none" stroke="#3DDC97" stroke-width="2"/>'+mug(206,76)+'<path d="M94 91 L200 91" stroke="#8FE8F2" stroke-width="2" stroke-dasharray="3 4"/><text x="148" y="82" fill="#E6EEFF" font-size="13" text-anchor="middle">0.62 m</text><text x="150" y="162" fill="#9AAED0" font-size="11" text-anchor="middle">upright, camera aimed at one thing</text>');},
listen:function(){return svg(ph(130,110,40,62)+'<g class="sweep"><path d="M150 168 L120 30 A140 140 0 0 1 180 30 Z" fill="#8FE8F2" fill-opacity=".14"/></g><path d="M60 150 Q150 60 240 150" fill="none" stroke="#2A3F66" stroke-dasharray="3 5"/><text x="150" y="20" fill="#9AAED0" font-size="11" text-anchor="middle">turn slowly, like a flashlight</text>');},
walk:function(){return svg('<rect x="0" y="0" width="70" height="180" fill="url(#gl1)"/><defs><linearGradient id="gl1"><stop offset="0" stop-color="#F2B84B" stop-opacity=".6"/><stop offset="1" stop-color="#F2B84B" stop-opacity="0"/></linearGradient></defs>'+ph(112,26,76,128)+'<text x="150" y="94" fill="#9AAED0" font-size="11" text-anchor="middle">screen stays dark</text><text x="260" y="96" fill="#9AAED0" font-size="22" text-anchor="middle">tick</text><text x="260" y="116" fill="#9AAED0" font-size="22" text-anchor="middle">tick</text><text x="40" y="170" fill="#F2B84B" font-size="11" text-anchor="middle">glow = close</text>');},
watch:function(){return svg(ph(104,128,92,30)+ringsA(150,143,40)+'<text x="150" y="30" fill="#9AAED0" font-size="11" text-anchor="middle">lying still on a shelf</text><circle cx="214" cy="70" r="7" fill="#F2B84B"/><text x="236" y="74" fill="#F2B84B" font-size="11">moved</text>');}
};
var TOUR=[
{art:"echo",h:"It listens for echoes",t:"Batsight sends a short, quiet chirp and listens for it to come back. How long that takes tells how far away something is."},
{art:"colours",h:"Four things to know on screen",t:"Green means clear and confirmed. A grey dashed \"?\" means it can't tell. Amber shows where something moved. A dashed blue-white outline means probable, not sure. Green is never a guess."},
{art:"glass",h:"Silence doesn't mean empty",t:"Smooth glass and mirrors can send the sound away, so nothing comes back. That is why \"?\" exists. If the screen says \"?\", trust your eyes and your hands."},
{art:"mic",h:"The mic is on only while listening",t:"A red \"Mic on\" sign shows whenever it is. The app sends nothing anywhere."}
];
var HELP={
F:{art:"focus",h:"Focus in the dark",steps:function(){return ["Hold the phone upright and aim the camera at one thing.","Tap Listen and keep still for two seconds.","Green number: focus locked. Grey \"?\": can't tell, focus by hand. Two numbers: tap the one you meant.","Stay at least "+nearTxt()+" from the thing. Closer than that, the phone hears itself."];},chips:[["--c:var(--ok)","Focus locked"],["--c:transparent;--b:1px dashed var(--unk)","Can't tell"]]},
L:{art:"listen",h:"Listen",steps:function(){return ["Hold the phone upright in front of you and tap Start.","Turn slowly left and right, like a flashlight. Echoes light up and fade.","Sweep the same spot twice. Green wedge means clear to 3 m, confirmed. Grey dashed \"?\" means can't tell (glass looks like this).","Direction on this phone is still being tested. Until it is proven, this screen says \"Experimental\"."];},chips:[["--c:var(--ok)","Confirmed clear"],["--c:#8FE8F2","Echo seen"],["--c:transparent;--b:1px dashed var(--unk)","Can't tell"]]},
W:{art:"walk",h:"Walk, eyes free",steps:function(){return ["Hold the phone at chest height, pointing ahead, and tap Start. The screen stays dark.","A soft glow shows what is close ahead. Ticks get faster as it gets closer. Very close: a buzz.","This is a test aid, not a safety device. It cannot see glass, and no glow does not mean the way is clear.","Never rely on it for roads, stairs or anything where a mistake could hurt."];},chips:[["--c:var(--warn)","Something close"]]},
H:{art:"watch",h:"Watch, hands free",steps:function(){return [PH==="8t"?"Lay the phone still on a shelf, long side facing the room, and tap Start.":"Lay the phone still on a shelf, anywhere it can hear the room, and tap Start.","Wait a few seconds while it learns the room. Then it says \"Still\".","An amber ring shows how far away something moved. Keep it about "+nearTxt()+" or more from the phone.","If a change stays, like a door left open, it asks \"Keep as normal?\"."];},chips:[["--c:var(--warn)","Where it moved"],["--c:var(--ok)","Still"]]}
};
var ovStep=0, ovMode=null;
function openOv(mode){ovMode=mode;ovStep=0;$("ov").hidden=false;renderOv();}
function closeOv(){$("ov").hidden=true;ovMode=null;}
function renderOv(){
var c=$("card"), html="";
if(ovMode==="tour"){var s=TOUR[ovStep];
html='<div class="art">'+ART[s.art]()+'</div><h2>'+s.h+'</h2><p class="txt">'+s.t+'</p><div class="dots">'+TOUR.map(function(_,i){return '<i class="'+(i===ovStep?'on':'')+'"></i>';}).join("")+'</div><div class="row end"><button class="b" id="oback"'+(ovStep===0?' hidden':'')+'>Back</button><button class="b" id="oskip">Skip</button><button class="b p" id="onext">'+(ovStep===TOUR.length-1?'Start':'Next')+'</button></div>';}
else{var h=HELP[ovMode];
html='<div class="art">'+ART[h.art]()+'</div><h2>'+h.h+'</h2><ol>'+h.steps().map(function(x){return '<li><span>'+x+'</span></li>';}).join("")+'</ol><div class="chips">'+h.chips.map(function(k){return '<span class="chip"><i style="'+k[0]+'"></i>'+k[1]+'</span>';}).join("")+'</div><div class="near">Nearest trusted distance on this phone: '+nearTxt()+'. Closer than that, results are marked too close.</div><div class="row end"><span></span><button class="b p" id="oclose">Got it</button></div>';}
c.innerHTML=html;
var n=$("onext"); if(n)n.onclick=function(){if(ovStep<TOUR.length-1){ovStep++;renderOv();}else{Bat.tourDone();closeOv();}};
var b=$("oback"); if(b)b.onclick=function(){ovStep=Math.max(0,ovStep-1);renderOv();};
var sk=$("oskip"); if(sk)sk.onclick=function(){Bat.tourDone();closeOv();};
var cl=$("oclose"); if(cl)cl.onclick=closeOv;
}

/* ================= Tabs ================= */
var TITLES={J:"Join",F:"Focus in the dark",L:"Listen",W:"Walk, eyes free",H:"Watch, hands free"}, tab="F";
function stopRunning(){ if(running){Bat.stop();running=null;mic(false);} resetButtons(); }
function setTab(t){
if(t===tab)return; stopRunning(); tab=t;
["J","F","L","W","H"].forEach(function(k){$("t"+k).setAttribute("aria-selected",k===t);$("s"+k).hidden=k!==t;});
if(t==="J")Bat.hiveJoin();
$("title").textContent=TITLES[t];
if(t==="F")camOn(); else camOff();
}
["J","F","L","W","H"].forEach(function(k){$("t"+k).addEventListener("click",function(){setTab(k);});});
$("help").addEventListener("click",function(){ if(tab==="J"){return;} openOv(tab);});
$("lab").addEventListener("click",function(e){e.preventDefault();stopRunning();camOff();Bat.lab();});
window.batBack=function(){ if(!$("ov").hidden){closeOv();return true;} if(running){stopRunning();return true;} if(tab!=="F"){setTab("F");return true;} return false; };
function resetButtons(){ $("lgo").textContent="Start"; $("wgo").textContent="Start"; $("hgo").textContent="Start"; }

/* ================= Screen 2: Focus ================= */
var track=null, caps={}, focusId=null, lastResult=null, picked=null, focusInfo=null;
function camOn(){
if(track||!navigator.mediaDevices||!navigator.mediaDevices.getUserMedia)return;
navigator.mediaDevices.getUserMedia({video:{facingMode:"environment"},audio:false}).then(function(s){
$("cam").srcObject=s; track=s.getVideoTracks()[0]; try{caps=track.getCapabilities?track.getCapabilities():{};}catch(e){caps={};}
}).catch(function(){ /* no camera picture: sound still works */ });
}
function camOff(){ if(track){try{track.stop();}catch(e){} track=null; $("cam").srcObject=null;} }
/* APP: Chat's test C - can the app set the lens from the sound distance? */
function focusTo(d){
focusInfo={asked_m:d,supported:!!(caps&&caps.focusDistance),caps:caps&&caps.focusDistance||null};
if(!track||!caps.focusDistance)return;
var fd=caps.focusDistance, v=Math.min(fd.max,Math.max(fd.min,d));
track.applyConstraints({advanced:[{focusMode:"manual",focusDistance:v}]}).then(function(){
setTimeout(function(){try{var st=track.getSettings();focusInfo.lens_reports=st.focusDistance;focusInfo.mode=st.focusMode;}catch(e){}},600);
}).catch(function(e){focusInfo.error=String(e);});
}
function autoFocus(){ if(track&&caps.focusMode&&caps.focusMode.indexOf("continuous")>=0){track.applyConstraints({advanced:[{focusMode:"continuous"}]}).catch(function(){});} }
function rings(){
var g=$("ringg"); g.innerHTML="";
for(var i=0;i<3;i++){
var c=document.createElementNS(NS,"circle");
c.setAttribute("cx",150);c.setAttribute("cy",430);c.setAttribute("fill","none");c.setAttribute("stroke","#8FE8F2");c.setAttribute("stroke-width","2");c.setAttribute("r",0);c.setAttribute("opacity",0);
g.appendChild(c);
if(c.animate)c.animate([{r:0,opacity:.8},{r:420,opacity:0}],{duration:1300,delay:i*380,easing:"ease-out",iterations:2});
}
}
function focusReset(){ $("pick").hidden=true; $("ask").hidden=true; $("frame").className=""; $("frame2").className=""; $("ringg").innerHTML=""; }
$("listen").addEventListener("click",function(){
if(running==="focus")return; running="focus"; $("listen").disabled=true; focusReset(); picked=null; focusInfo=null; autoFocus();
mic(true); $("dist").innerHTML="&nbsp;"; $("status").className="s-idle"; $("status").textContent="Listening…";
$("note").textContent="Sending a short sound and listening for the echo. Keep still."; rings();
Bat.focusListen();
});
function lockAt(d,mine){
$("dist").innerHTML=d.toFixed(2)+"<small>m</small>"; $("status").className="s-ok";
$("status").textContent=mine?"Focus locked on your pick":"Focus locked"; $("frame").className="ok"; $("frame2").className=""; focusTo(d);
}
window.batFocus=function(r){
running=null; mic(false); $("listen").disabled=false; $("ringg").innerHTML=""; lastResult=r; focusId=r.id;
if(r.state==="two"&&!(r.picks&&r.picks.length>=2)&&r.d!=null)r.state="echo";
if(r.state==="echo"&&r.d!=null){ lockAt(r.d,false); $("note").textContent="Locked at "+r.d.toFixed(2)+" m."; $("askq").textContent="Was that the thing you aimed at?"; $("ask").hidden=false; }
else if(r.state==="two"&&r.picks&&r.picks.length>=2){
var n=r.picks.length;
$("dist").innerHTML=n+"<small>things</small>"; $("status").className="s-two"; $("status").textContent=(n===3?"Three":"Two")+" things found"; $("frame").className="two"; $("frame2").className="on";
var p=$("pick"); p.innerHTML=""; r.picks.forEach(function(ds,i){var d=+ds,b=document.createElement("button"); b.setAttribute("data-d",d); b.innerHTML=(i===0?"Nearer":(i===n-1?"Farther":"Middle"))+"<b>"+d.toFixed(2)+" m</b>"; p.appendChild(b);}); p.hidden=false;
$("note").textContent=(n===3?"Three":"Two")+" things are in line with where you aimed. Pick the one you want sharp. Nothing is chosen for you."+(r.more?" More behind.":"");
}
else if(r.state==="close"){ $("dist").textContent="?"; $("status").className="s-unk"; $("status").textContent="Too close"; $("frame").className="unk";
$("note").innerHTML="Closer than "+nearTxt()+", the phone hears itself. Step back a little and try again."; $("askq").textContent="Was the thing really that close?"; $("ask").hidden=false; }
else if(r.state==="error"){ $("dist").innerHTML="&nbsp;"; $("status").className="s-unk"; $("status").textContent="Try again"; $("note").textContent="Something went wrong with the sound. Tap Listen again."; }
else { $("dist").textContent="?"; $("status").className="s-unk"; $("status").textContent="Can't tell"; $("frame").className="unk";
$("note").textContent="Can't tell. Nothing clear came back. Smooth glass sends the sound away from the phone, so silence here does not mean empty. Focus by hand, or move the phone so you are square to it.";
$("askq").textContent="Was there something there?"; $("ask").hidden=false; }
};
$("pick").addEventListener("click",function(e){
var b=e.target.closest("button"); if(!b)return; var d=+b.getAttribute("data-d"), near=b===$("pick").firstChild;
picked=d; $("pick").hidden=true; lockAt(d,true);
$("note").textContent="You chose the "+(near?"nearer":"farther")+" one at "+d.toFixed(2)+" m. Green means you picked it and the echo is clear; the app did not guess.";
$("askq").textContent="Was that the thing you aimed at?"; $("ask").hidden=false;
});
$("ask").addEventListener("click",function(e){
var b=e.target.closest("button"); if(!b||!focusId)return;
var a=b.getAttribute("data-a");
setTimeout(function(){ Bat.save(focusId+"_answer.json",JSON.stringify({answer:a,question:$("askq").textContent,state:lastResult&&lastResult.state,shown_m:lastResult&&lastResult.d,picked_m:picked,focus_test:focusInfo})); },700);
$("ask").hidden=true; $("note").textContent=$("note").textContent+" Thanks, saved.";
});

/* ================= Screen 1: Listen (Chat's drawing, real echoes) ================= */
var R_PX=80, OX=150, OY=285, radar=$("radar");
function el(tag,at,p){var e=document.createElementNS(NS,tag);for(var k in at)e.setAttribute(k,at[k]);(p||radar).appendChild(e);return e;}
[1,2,3].forEach(function(m){
el("path",{d:"M"+(OX-m*R_PX)+" "+OY+" A"+m*R_PX+" "+m*R_PX+" 0 0 1 "+(OX+m*R_PX)+" "+OY,fill:"none",stroke:"#2A3F66","stroke-width":1,"stroke-dasharray":"2 4"});
var t=el("text",{x:OX+m*R_PX-4,y:OY-4,fill:"#7F93B8","font-size":9,"text-anchor":"end"});t.textContent=m+" m";
});
el("circle",{cx:OX,cy:OY,r:6,fill:"#4D8DFF"});
var layer=el("g",{}), beam=el("path",{fill:"#8FE8F2","fill-opacity":.10,stroke:"none"});
var mem={}, heading=0, h0=null;
var NOW=function(){return performance.now()};
function visit(h,s){   /* APP: s = the real reading at this heading */
var t=NOW();
for(var a=Math.round(h)-4;a<=Math.round(h)+4;a++){
if(a<-90||a>90)continue;
var m=mem[a]||(mem[a]={passes:0,last:-1e9,t:0,type:"",d:0});
if(t-m.last>1200)m.passes++;
m.last=t; m.t=t; m.type=s.t; m.d=s.d||0;
}
}
function pt(a,r){var th=a*Math.PI/180;return [OX+Math.sin(th)*r*R_PX, OY-Math.cos(th)*r*R_PX];}
function wedge(a,r0,r1,fill,op,dash){
var p0=pt(a-1,r0),p1=pt(a-1,r1),p2=pt(a+1,r1),p3=pt(a+1,r0);
return '<path d="M'+p0+' L'+p1+' A'+r1*R_PX+' '+r1*R_PX+' 0 0 1 '+p2+' L'+p3+' Z" fill="'+fill+'" fill-opacity="'+op+'"'+(dash?' stroke="#7F8DA8" stroke-opacity="'+op+'" stroke-dasharray="3 3" stroke-width="1"':'')+'/>';
}
function draw(){
var t=NOW(), out="";
for(var k in mem){
var m=mem[k], a=+k, age=(t-m.t)/1000;
if(m.type==="echo"){ var op=Math.max(0,1-age/6); if(op>0){out+=wedge(a,Math.max(0.1,m.d-0.08),m.d+0.08,"#8FE8F2",op.toFixed(2));} }
else if(m.type==="far"){ if(m.passes>=2)out+=wedge(a,0.1,3,"#3DDC97",.35); else out+=wedge(a,0.1,3,"#7F8DA8",.12,true); }
else { out+=wedge(a,0.1,3,"#7F8DA8",.16,true); }
}
layer.innerHTML=out;
var p0=pt(heading-12,3.3),p1=pt(heading+12,3.3);
beam.setAttribute("d","M"+OX+" "+OY+" L"+p0+" A"+3.3*R_PX+" "+3.3*R_PX+" 0 0 1 "+p1+" Z");
if(running==="listen"){
var big="Sweep the phone slowly", sub="Echoes light up where you point and fade.", cur=mem[Math.round(heading)];
if(cur){ if(cur.type==="echo"){big="Something at "+cur.d.toFixed(1)+" m";sub="Echo seen in this direction.";}
else if(cur.type==="far"){ if(cur.passes>=2){big="Clear to 3 m";sub="Confirmed on two passes: the sound went past 3 m and came back.";} else {big="Looks clear, not yet sure";sub="Sweep this way once more to confirm.";} }
else {big="Can't tell";sub="Nothing came back at all. That can be open space or smooth glass.";} }
if(Math.abs(heading)>90){big="Turn back toward the start";sub="This map shows the half in front of where you started.";}
$("r-big").textContent=big; $("r-sub").textContent=sub;
}
requestAnimationFrame(draw);
}
draw();
$("lgo").addEventListener("click",function(){
if(running==="listen"){stopRunning();$("r-big").textContent="Stopped";$("r-sub").textContent="Tap Start to listen again.";return;}
stopRunning(); running="listen"; h0=null; mic(true); $("lgo").textContent="Stop"; Bat.liveStart("listen");
$("r-big").textContent="Listening…"; $("r-sub").textContent="Hold still a moment, then turn slowly.";
});
$("rclear").addEventListener("click",function(){mem={};h0=null;});

/* ================= Screen 3: Walk (Chat's drawing, real nearest echo, ahead only) ================= */
var ac=null, soundOn=false, tickT=0, nearW=null, lastBuzz=0;
function glowOp(d){return d===null||d>2.5?0:Math.min(0.9,(2.5-d)/2.2);}
function updWalk(){
var n=nearW, st="Screen stays dark", sub="Nothing close that I can hear. That does not mean the way is clear.";
if(running!=="walk"){st="Screen stays dark";sub="Tap Start. A soft glow shows what is close ahead. Ticks speed up as it gets closer.";n=null;}
else if(n!==null){ if(n<0.4){st="Very close ahead";sub="Buzz.";} else if(n<1.2){st="Close ahead";sub=n.toFixed(1)+" m";} else {st="Something ahead";sub=n.toFixed(1)+" m";} }
$("gc").style.opacity=glowOp(n); $("wstate").textContent=st; $("wsub").textContent=sub;
$("buzz").style.opacity=(n!==null&&n<0.4)?1:0;
if(n!==null&&n<0.4&&NOW()-lastBuzz>600){lastBuzz=NOW();Bat.buzz(60);}
schedTick();
}
function tick(){
if(!ac||!soundOn)return;
var o=ac.createOscillator(),g=ac.createGain();
o.type="sine";o.frequency.value=1100;g.gain.value=0.0001;o.connect(g);g.connect(ac.destination);
var t=ac.currentTime;g.gain.exponentialRampToValueAtTime(0.09,t+0.005);g.gain.exponentialRampToValueAtTime(0.0001,t+0.05);   /* APP: 0.09 = -21 dBFS peak (sound rule) */
o.start(t);o.stop(t+0.06);
}
function schedTick(){
clearTimeout(tickT);
var n=nearW; if(n===null||!soundOn||running!=="walk")return;
var iv=Math.max(90,Math.min(1100,260*n+60));
tickT=setTimeout(function(){tick();schedTick();},iv);
}
$("sound").addEventListener("click",function(){
if(!soundOn){var AC=window.AudioContext||window.webkitAudioContext;if(AC&&!ac)ac=new AC();if(ac&&ac.resume)ac.resume();soundOn=true;$("sound").textContent="Turn off tick sound";}
else{soundOn=false;$("sound").textContent="Turn on tick sound";}
schedTick();
});
$("wgo").addEventListener("click",function(){
if(running==="walk"){stopRunning();nearW=null;updWalk();return;}
stopRunning(); running="walk"; nearW=null; mic(true); $("wgo").textContent="Stop"; Bat.liveStart("walk"); updWalk();
});

/* live pings for Listen and Walk */
window.batLive=function(p){
if(running==="listen"){
if(h0===null)h0=p.h;
var rel=((p.h-h0+540)%360)-180; heading=rel;
if(Math.abs(rel)<=90) visit(rel, p.d!=null?{t:"echo",d:p.d}:(p.far?{t:"far"}:{t:"none"}));
} else if(running==="walk"){ nearW=(p.d!=null)?p.d:null; updWalk(); }
};

/* ================= Screen 4: Watch (Chat's drawing, real watch engine, distance only) ================= */
var WOX=150, WOY=270, WS=60, wmap=$("wmap"), since=Date.now(), marks=[], changed=false;
function wel(tag,at,p){var e=document.createElementNS(NS,tag);for(var k in at)e.setAttribute(k,at[k]);(p||wmap).appendChild(e);return e;}
[1,2,3].forEach(function(m){
wel("path",{d:"M"+(WOX-m*WS)+" "+WOY+" A"+m*WS+" "+m*WS+" 0 0 1 "+(WOX+m*WS)+" "+WOY,fill:"none",stroke:"#2A3F66","stroke-width":1,"stroke-dasharray":"2 4"});
var t=wel("text",{x:WOX+m*WS-4,y:WOY-4,fill:"#7F93B8","font-size":9,"text-anchor":"end"});t.textContent=m+" m";
});
var pingG=wel("g",{fill:"none",stroke:"#8FE8F2"}), markG=wel("g",{});
var soloDot=wel("circle",{cx:WOX,cy:WOY,r:6,fill:"#4D8DFF"});
function wp(a,r){var th=a*Math.PI/180;return [(WOX+Math.sin(th)*r*WS).toFixed(1),(WOY-Math.cos(th)*r*WS).toFixed(1)];}
function band(a0,a1,r0,r1,fill,op,stroke,dash){
var p0=wp(a0,r0),p1=wp(a0,r1),p2=wp(a1,r1),p3=wp(a1,r0);
return '<path d="M'+p0+' L'+p1+' A'+r1*WS+' '+r1*WS+' 0 0 1 '+p2+' L'+p3+' A'+r0*WS+' '+r0*WS+' 0 0 0 '+p0+' Z" fill="'+fill+'" fill-opacity="'+op+'" stroke="'+stroke+'" stroke-opacity="'+(dash?0.8:0)+'" '+(dash?'stroke-dasharray="4 3"':'')+' stroke-width="1.2"/>';
}
function drawMarks(){
var out="", n=marks.length;
marks.forEach(function(p,i){ var age=n-1-i, op=Math.max(0.12,1-age*0.22), last=(i===n-1);
out+=band(-90,90,p.d-0.2,p.d+0.2,"#F2B84B",((p.solid?0.3:0.14)*op).toFixed(2),"#F2B84B",last); });
var m=moveOf(marks); if(m)out+=wArrow(WOX,WOY-marks[n-1].d*WS,0,m.vr<0?1:-1,m.sp,"#F2B84B");
markG.innerHTML=out; $("w-move").textContent=m?moveLine("Mover",m):"";
}
/* speed toward/away from the last few timed distances (Chat, Oct 19: speed + arrow; Faro: toward/away only) */
var MOVE_MIN=0.12;
function moveOf(pts){
if(pts.length<3)return null;
var k=Math.min(4,pts.length), a=pts[pts.length-k], b=pts[pts.length-1], dt=(b.t-a.t)/1000;
if(!(dt>0.3))return null;
var vr=(b.d-a.d)/dt, sp=Math.abs(vr);
return sp>=MOVE_MIN&&sp<5?{vr:vr,sp:sp}:null;
}
function speedWord(v){return v<0.35?"slow":v<1.3?"walking pace":"fast";}
function moveLine(who,m){return who+": "+speedWord(m.sp)+", about "+m.sp.toFixed(1)+" m/s "+(m.vr<0?"toward":"away from")+" the phone.";}
function wArrow(x,y,ux,uy,sp,col){
var L=Math.min(sp*1.6*WS,70); if(L<6)return "";
var x1=x+ux*L, y1=y+uy*L, hx=x1-ux*8, hy=y1-uy*8;
return '<line x1="'+x+'" y1="'+y.toFixed(1)+'" x2="'+hx.toFixed(1)+'" y2="'+hy.toFixed(1)+'" stroke="'+col+'" stroke-width="2.2" stroke-linecap="round" opacity="0.95"/>'+
'<polygon points="'+x1.toFixed(1)+','+y1.toFixed(1)+' '+(hx-uy*4.5).toFixed(1)+','+(hy+ux*4.5).toFixed(1)+' '+(hx+uy*4.5).toFixed(1)+','+(hy-ux*4.5).toFixed(1)+'" fill="'+col+'" opacity="0.95"/>';
}
function pulse(){ if(running!=="watch")return;
var c=wel("circle",{cx:WOX,cy:WOY,r:6,"stroke-width":1.5,opacity:.5},pingG);
if(c.animate){var a=c.animate([{r:6,opacity:.4},{r:190,opacity:0}],{duration:2600,easing:"ease-out"});a.onfinish=function(){c.remove()};}else c.remove();
}
setInterval(pulse,2000);
function wStatus(big,col,sub){$("w-big").textContent=big;$("w-big").style.color=col;$("w-sub").textContent=sub;}
setInterval(function(){ if(running==="watch"&&!changed&&!marks.length&&$("w-big").textContent==="Still"){wStatus("Still","var(--ok)","Nothing has moved for "+Math.round((Date.now()-since)/1000)+" s.");} },1000);
window.batWatch=function(ev){
if(running!=="watch")return;
if(ev.state==="turn"){ $("w-sub").textContent=ev.text||"Taking turns with the other phone."; return; }
if(inHive()&&meTag){ hw[meTag]={state:ev.state,d:ev.d,at:Date.now()}; if(ev.state==="MOVING"||ev.state==="CHANGED"){ hiveDraw(); return; } }
if(ev.state==="learning"){wStatus("Learning the room","var(--muted)","Keep still for a few seconds.");return;}
if(ev.state==="MOVING"&&ev.d!=null){changed=false;$("w-ask2").hidden=true;marks.push({d:ev.d,t:Date.now()});if(marks.length>5)marks.shift();drawMarks();
wStatus("Something moved","var(--warn)","About "+ev.d.toFixed(1)+" m away. This phone can't tell which way yet.");since=Date.now();return;}
if(ev.state==="CHANGED"&&ev.d!=null){if(!changed){changed=true;marks=[{d:ev.d,t:Date.now(),solid:true}];drawMarks();}
wStatus("Something changed","var(--warn)","Something stayed different, about "+ev.d.toFixed(1)+" m away. Keep it as the new normal?");$("w-ask2").hidden=false;return;}
if(ev.state==="ADOPTED"){changed=false;marks=[];drawMarks();$("w-ask2").hidden=true;wStatus("Still","var(--ok)","The change stayed, so it is now the usual room.");since=Date.now();return;}
if(ev.state==="still"&&!changed){ if(marks.length){marks.shift();drawMarks();} if(!marks.length&&$("w-big").textContent!=="Still"){wStatus("Still","var(--ok)","Nothing has moved for 0 s.");since=Date.now();} }
};
$("w-yes").addEventListener("click",function(){Bat.watchKeep();changed=false;marks=[];drawMarks();$("w-ask2").hidden=true;since=Date.now();wStatus("Still","var(--ok)","Kept as the new normal. Watching again.");});
$("w-no").addEventListener("click",function(){$("w-ask2").hidden=true;$("w-sub").textContent+=" Still warning, as you asked.";});
function inHive(){ return !!(meTag&&otherTag); }
function startWatch(tell){
stopRunning(); running="watch"; marks=[]; changed=false; drawMarks(); mic(true); $("hgo").textContent="Stop"; since=Date.now(); hw={};
wStatus("Learning the room","var(--muted)","Keep still for a few seconds."); Bat.watchStart();
if(inHive()){ if(tell)Bat.hiveSend(JSON.stringify({t:"watch",on:true})); $("miclabel").textContent="Mic on: "+[meTag,otherTag].sort().join(", "); hiveBase(); }
}
function stopWatch(tell){
stopRunning();marks=[];changed=false;drawMarks();$("w-ask2").hidden=true;wStatus("Not watching","var(--muted)","Stopped. Tap Start to watch again.");
if(inHive()&&tell)Bat.hiveSend(JSON.stringify({t:"watch",on:false})); hiveG.innerHTML="";
}
$("hgo").addEventListener("click",function(){ if(running==="watch")stopWatch(true); else startWatch(true); });

/* ---- APP: hive merged map (Chat: Shared scanning screens, Watch). Phones on the bottom line, the handshake
   distance apart; a mover heard by both sits where their distance circles cross, on the room side. ---- */
var hiveG=wel("g",{}), hw={}, trail=[];
function pX(tag){ var d=hiveD||0.6, left=(tag===[meTag,otherTag].sort()[0]); return WOX+(left?-1:1)*d/2*WS; }
function hiveBase(){ hiveG.innerHTML=""; soloDot.setAttribute("opacity",inHive()?0:1); [meTag,otherTag].forEach(function(t){ tagSvg(t,pX(t),WOY+12,hiveG,8); }); }
function remoteW(ev){ hw[ev.tag]={state:ev.state,d:ev.d,at:Date.now()}; hiveDraw(); }
function hiveDraw(){
if(!inHive()||running!=="watch")return;
hiveBase();
var now=Date.now(), mv=[meTag,otherTag].map(function(t){var e=hw[t];return (e&&(e.state==="MOVING"||e.state==="CHANGED")&&e.d!=null&&now-e.at<1500)?{t:t,d:e.d}:null;}).filter(Boolean);
var g=hiveG, ring=function(t,r,dash){ jel("circle",{cx:pX(t),cy:WOY+12,r:r*WS,fill:"none",stroke:COL[t],"stroke-opacity":0.7,"stroke-width":1.4,"stroke-dasharray":dash?"4 4":""},g); };
if(mv.length===2){
 var A=mv[0],B=mv[1], xa=(pX(A.t)-WOX)/WS, xb=(pX(B.t)-WOX)/WS;
 var x=(A.d*A.d-B.d*B.d+xb*xb-xa*xa)/(2*(xb-xa)), yy=A.d*A.d-(x-xa)*(x-xa), approx=yy<0, y=Math.sqrt(Math.max(0,yy));
 ring(A.t,A.d,false); ring(B.t,B.d,false);
 trail.push([WOX+x*WS,WOY+12-y*WS,(A.d+B.d)/2,now]); if(trail.length>5)trail.shift();
 for(var i=1;i<trail.length;i++)jel("line",{x1:trail[i-1][0],y1:trail[i-1][1],x2:trail[i][0],y2:trail[i][1],stroke:"#F2B84B","stroke-width":2,"stroke-opacity":(0.2+0.6*i/trail.length).toFixed(2),"stroke-linecap":"round"},g);
 trail.forEach(function(p,i){ jel("circle",{cx:p[0],cy:p[1],r:i===trail.length-1?7:3.5,fill:"#F2B84B","fill-opacity":(0.25+0.75*(i+1)/trail.length).toFixed(2)},g); });
 var hm=moveOf(trail.map(function(q){return {d:q[2],t:q[3]};})), tp=trail[trail.length-1];
 if(hm){var dx=tp[0]-WOX, dy=tp[1]-(WOY+12), L=Math.sqrt(dx*dx+dy*dy)||1, sg=hm.vr<0?-1:1; g.insertAdjacentHTML("beforeend",wArrow(tp[0],tp[1],dx/L*sg,dy/L*sg,hm.sp,"#F2B84B"));}
 $("w-move").textContent=hm?moveLine("Mover",hm):"";
 wStatus("Something moved","var(--warn)","About "+A.d.toFixed(1)+" m from "+A.t+" and "+B.d.toFixed(1)+" m from "+B.t+"."+(approx?" The two distances don't quite meet, so the spot is approximate.":""));
} else if(mv.length===1){
 ring(mv[0].t,mv[0].d,true);
 wStatus("Something moved","var(--warn)","Heard by "+mv[0].t+" only: about "+mv[0].d.toFixed(1)+" m from it. Which way is unknown.");
}
}
/* ================= Join (Chat: Shared scanning screens) ================= */
var COL={A:"#8FE8F2",B:"#FF9EC7",C:"#C7B8FF"}, SH={A:"circle",B:"square",C:"triangle"}, NAMEC={A:"cyan",B:"pink",C:"violet"};
function jel(t,a,p){var e=document.createElementNS(NS,t);for(var k in a)e.setAttribute(k,a[k]);p.appendChild(e);return e}
function tagSvg(id,x,y,p,r){r=r||8;var c=COL[id]||"#9AAED0";
if(SH[id]==="circle")jel("circle",{cx:x,cy:y,r:r,fill:c},p);else if(SH[id]==="square")jel("rect",{x:x-r,y:y-r,width:2*r,height:2*r,rx:2,fill:c},p);
else jel("path",{d:"M"+x+" "+(y-r-1)+" L"+(x+r+1)+" "+(y+r)+" L"+(x-r-1)+" "+(y+r)+" Z",fill:c},p);
var t=jel("text",{x:x,y:y+4,"text-anchor":"middle","font-size":11,"font-weight":600,fill:"#06101F"},p);t.textContent=id;}
var meTag=null, otherTag=null, hiveD=null;
function jdraw(found){var g=$("jsvg");g.innerHTML="";
jel("line",{x1:70,y1:80,x2:230,y2:80,stroke:"#2A3F66","stroke-dasharray":"3 5"},g);
[[52,meTag||"A"],[248,otherTag||(meTag==="A"?"B":"A")]].forEach(function(q){var x=q[0],id=q[1];
jel("rect",{x:x-18,y:40,width:36,height:80,rx:7,fill:"#101D33",stroke:COL[id],"stroke-width":2},g);tagSvg(id,x,80,g);});
if(found){[0,1,2].forEach(function(i){jel("circle",{cx:52+i*8,cy:80,r:30+i*34,fill:"none",stroke:"#8FE8F2","stroke-opacity":(0.45-i*0.12)},g)});}}
function jset(h,p,big,sub){$("j-h").textContent=h;$("j-p").textContent=p;$("j-big").textContent=big;$("j-sub").textContent=sub}
function showMyTag(){var m=$("mytag"); if(!meTag){m.className="";return;} m.innerHTML='<svg width="20" height="20" viewBox="0 0 20 20"><g id="mtg"></g></svg>Phone '+meTag; tagSvg(meTag,10,10,m.querySelector("#mtg"),8); m.className="on";}
function jready(peers){
if(peers>0){jset("1. Face the phones","Hold the phones facing each other, about 20 to 60 cm apart. Not side by side.","Ready","Both phones are on this screen.");$("j-go").disabled=false;}
else{jset("1. Face the phones","Open Join on both phones. Hold them facing each other, about 20 to 60 cm apart. Not side by side.","Looking for the other phone…","Open Batsight on the other phone and tap Join there too.");$("j-go").disabled=true;}
}
window.batHive=function(ev){
if(ev.type==="peers"){ meTag=ev.me; if(ev.peers>0){otherTag=(ev.tags||"").split(",")[0]||null;} showMyTag(); jdraw(false);
 if($("j-next").hidden&&!running) jready(ev.peers);
 if(ev.dropped){ $("j-sub").textContent="The other phone dropped out."; } return; }
if(ev.type==="msg"){
 if(ev.t==="watch"){ if(ev.on&&running!=="watch"){ if(tab!=="H")setTab("H"); startWatch(false);} else if(!ev.on&&running==="watch"){ stopWatch(false);} return; }
 if(ev.t==="w"&&ev.tag){ remoteW(ev); return; }
 return; }
if(ev.type==="error"){ jset("Can't reach the other phone","The nearby link didn't start ("+(ev.why||ev.where)+"). Check Bluetooth is on, then tap Join again.","Not connected",""); return; }
if(ev.type==="hsgo"){ running="hs"; mic(true); $("j-go").hidden=true; jdraw(true); jset("2. Listen to each other","Keep still for a few seconds. You will hear short chirps, like birds.","Chirping…","About 3 seconds."); return; }
if(ev.type==="hserr"){ running=null; mic(false); $("j-go").hidden=false; jdraw(false); jset("1. Face the phones","They didn't hear each other. Hold them facing each other, 20 to 60 cm apart, and try again.","Try again","Both phones must be on this screen."); return; }
if(ev.type==="hs"){ running=null; mic(false); meTag=ev.me; otherTag=ev.other; hiveD=ev.d; showMyTag(); jdraw(false);
 jset("3. Is this your other phone?","Check that the other phone shows the same name tag: the "+(NAMEC[ev.other]||"")+" "+(SH[ev.other]||"")+" "+ev.other+".","Found: "+(NAMEC[ev.other]||"")+" "+(SH[ev.other]||"")+" "+ev.other,ev.d.toFixed(2)+" m away, facing you.");
 $("j-next").hidden=false;$("j-no").hidden=false; return; }
};
$("j-go").addEventListener("click",function(){ if($("j-go").disabled)return; running="hs"; mic(true); $("j-go").hidden=true; jdraw(true);
jset("2. Listen to each other","Keep still for a few seconds. You will hear short chirps, like birds.","Chirping…","About 3 seconds."); Bat.hiveHandshake(); });
$("j-no").addEventListener("click",function(){$("j-next").hidden=true;$("j-no").hidden=true;$("j-go").hidden=false;jready(1);jdraw(false);});
$("j-next").addEventListener("click",function(){ $("j-next").hidden=true;$("j-no").hidden=true;
jset("4. Place them in the room","Put each phone where it will stay, with its long side facing the room.","Joined as "+meTag+" and "+otherTag,"Next, choose a screen above. Shared maps come in the next update.");
$("j-go").hidden=false;$("j-go").textContent="Start again"; });
jdraw(false);

/* ================= start ================= */
camOn();
if(!Bat.tourSeen()) openOv("tour");
})();
