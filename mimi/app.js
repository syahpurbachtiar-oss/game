(() => {
'use strict';
const E = window.MimiEngine;
const $ = id => document.getElementById(id);
const icon = name => `<svg class="icon" aria-hidden="true"><use href="#i-${name}"/></svg>`;
const safe = value => String(value).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
let state, storageOK = true;
try { state = E.normalize(JSON.parse(localStorage.getItem(E.KEY))); } catch { state = E.create(); }
E.advance(state);
let roomName = state.sleeping ? 'bedroom' : 'living';
let actionBusy = false, actionTimeout, toastTimeout, speechTimeout, saveTimeout, lastFocus;
let modalKind = '', game = null, frame = 0, gameTimeout = 0;
const transientTimers = new Set();
let audioContext = null, recorder = null, mediaStream = null, recordTimeout = null, voiceSource = null;
let voicePhase = 'idle', voiceToken = 0;
const rooms = {
 living: {label:'Ruang santai', icon:'home', title:'Waktunya bersenang-senang', subtitle:'Hal kecil yang bikin Mimi bahagia.'},
 kitchen: {label:'Dapur mungil', icon:'bowl', title:'Mau makan apa hari ini?', subtitle:'Pilih camilan kesukaan Mimi. Biskuit selalu gratis.'},
 bath: {label:'Kamar mandi', icon:'bath', title:'Bersih, wangi, disayang', subtitle:'Segarkan Mimi setelah seharian bermain.'},
 bedroom: {label:'Kamar tidur', icon:'moon', title:'Istirahat dulu, yuk', subtitle:'Tidur memulihkan energi. Mimi bangun saat segar.'}
};
function later(fn, ms) { const id = setTimeout(() => { transientTimers.delete(id); fn(); }, ms); transientTimers.add(id); return id; }
function persist() {
 clearTimeout(saveTimeout);
 try { localStorage.setItem(E.KEY, JSON.stringify(state)); storageOK = true; }
 catch { storageOK = false; }
 $('saveLabel').classList.toggle('error', !storageOK);
 $('saveLabel').innerHTML = storageOK ? '<span></span> Progres tersimpan otomatis' : '<span></span> Penyimpanan browser tidak tersedia';
}
function saveSoon() { clearTimeout(saveTimeout); saveTimeout = setTimeout(persist, 250); }
function toast(text) { clearTimeout(toastTimeout); $('toast').textContent = text; $('toast').classList.add('show'); toastTimeout = setTimeout(() => $('toast').classList.remove('show'), 3300); }
function say(text, ms = 5200) {
 clearTimeout(speechTimeout); $('speechBubble').textContent = text;
 if (ms) speechTimeout = setTimeout(defaultSpeech, ms);
}
function defaultSpeech() {
 const n = state.name;
 let phrase = 'Aku paling suka kalau kamu di sini. ♡';
 if (state.sleeping) phrase = 'Zzz… mimpi penuh ikan. ♡';
 else if (state.hunger < 35) phrase = 'Perutku bunyi, yuk ke dapur!';
 else if (state.hygiene < 35) phrase = 'Habis main, aku mau mandi dulu.';
 else if (state.energy < 30) phrase = 'Huaaam… aku butuh tidur.';
 else if (roomName === 'kitchen') phrase = 'Wah, ada yang enak buat aku?';
 else if (roomName === 'bath') phrase = 'Gelembung sabunnya lucu banget!';
 else if (roomName === 'bedroom') phrase = 'Bantal empukku sudah menunggu.';
 else if (state.fun < 35) phrase = 'Main bareng aku, yuk!';
 $('speechBubble').textContent = phrase;
 $('pet').setAttribute('aria-label', `Elus ${n}`);
}
function playTone(kind = 'tap') {
 if (!state.sound) return;
 try {
  const ac = getAudio(); if (!ac) return;
  ac.resume().catch(() => {});
  const notes = kind === 'reward' ? [523,659,784,1047] : kind === 'bad' ? [220,180] : kind === 'wash' ? [780,990] : [660,880];
  notes.forEach((hz,i) => { const osc=ac.createOscillator(), gain=ac.createGain(), t=ac.currentTime+i*.085;
   osc.type='sine'; osc.frequency.setValueAtTime(hz,t); gain.gain.setValueAtTime(0,t); gain.gain.linearRampToValueAtTime(.035,t+.012); gain.gain.exponentialRampToValueAtTime(.001,t+.16); osc.connect(gain); gain.connect(ac.destination); osc.start(t); osc.stop(t+.18);
  });
 } catch { /* Sound is optional. */ }
}
function getAudio() { const C = window.AudioContext || window.webkitAudioContext; if (!C) return null; if (!audioContext || audioContext.state === 'closed') audioContext = new C(); return audioContext; }
function update() {
 $('coinCount').textContent = state.coins.toLocaleString('id-ID');
 $('petName').innerHTML = `${safe(state.name)} <span>♡</span>`;
 $('levelBadge').textContent = `Lv. ${E.level(state)}`;
 $('xpBar').style.width = `${state.xp % 100}%`;
 $('xpLabel').textContent = `${state.xp % 100} / 100 XP`;
 $('moodLabel').textContent = state.sleeping ? 'Sedang bermimpi indah' : Math.min(state.hunger,state.energy,state.hygiene)<30 ? 'Butuh perhatianmu' : 'Senang ketemu kamu';
 for (const k of ['hunger','fun','hygiene','energy']) {
  const v=Math.round(state[k]); $(k+'Value').textContent=v+'%'; $(k+'Bar').style.width=v+'%';
  $(k+'Track').setAttribute('aria-valuenow',v); $(k+'Track').closest('.need').classList.toggle('low',v<30);
 }
 $('pet').dataset.hat=state.hat;
 $('pet').classList.toggle('sleeping',state.sleeping); $('room').classList.toggle('is-sleeping',state.sleeping);
 $('petHint').textContent=state.sleeping?'Mimi memulihkan energi':'Sentuh Mimi untuk mengelusnya';
 $('soundButton').innerHTML=icon(state.sound?'volume':'muted');
 $('soundButton').setAttribute('aria-label',state.sound?'Matikan suara':'Aktifkan suara');
 $('giftDot').hidden=state.gift===state.day && (!E.missionReady(state)||state.daily.claimed);
 $('missionList').innerHTML=missionRows();
 $('missionButton').classList.toggle('ready',E.missionReady(state)&&!state.daily.claimed);
 $('missionButton').innerHTML=`<span>${icon(state.daily.claimed?'check':'gift')} ${state.daily.claimed?'Sudah diambil':'Hadiah misi'}</span><b>60 ${icon('coin')}</b>`;
 saveSoon();
}
function missionRows() {
 return [['feed','Beri makan 2 kali',2],['wash','Mandi atau sisiran',1],['game','Selesaikan mini-game',1]].map(([k,label,total]) => {
  const done=state.daily[k]>=total; return `<div class="mission-row ${done?'done':''}"><span>${done?icon('check'):''}</span><span>${label}</span><b>${Math.min(total,state.daily[k])}/${total}</b></div>`;
 }).join('');
}
function actionItem(action,label,art,sub,color,arg='') {return `<button class="action-item" data-action="${action}" data-choice="${arg}" style="--item-color:${color}">${icon(art)}<b>${label}</b><small>${sub}</small></button>`;}
function renderTray() {
 let html='';
 if(roomName==='kitchen') html=Object.entries(E.FOODS).map(([key,f],i)=>actionItem('feed',f.name,f.icon,f.price?`${f.price} ${icon('coin')}`:'Gratis · +22 kenyang',['#bea076','#8ba6a3','#b7a48a','#d69c9d'][i],key)).join('');
 if(roomName==='living') html=actionItem('pet','Elus Mimi','heart','Tambah bahagia','#cb969b')+actionItem('play','Bola benang','yarn','Main sebentar','#ac9eba')+actionItem('games','Mini-game','game','Kumpulkan koin','#90a681')+actionItem('shop','Aksesori','wardrobe','Gaya baru','#c19a78');
 if(roomName==='bath') html=actionItem('wash','Mandi','shower','+38 bersih','#85aea9')+actionItem('brush','Sisiran','brush','+18 bersih','#c0a184')+actionItem('pet','Puk-puk','heart','Tambah bahagia','#cb969b')+actionItem('kitchen','Camilan','bowl','Ke dapur','#c2aa77');
 if(roomName==='bedroom') html=actionItem('sleep',state.sleeping?'Bangun':'Tidur',state.sleeping?'sun':'moon',state.sleeping?'Selamat pagi!':'Pulihkan energi','#a09bb9')+actionItem('pet','Peluk dulu','heart','Tambah bahagia','#cb969b')+actionItem('shop','Aksesori','wardrobe','Pilih favorit','#bca17f')+actionItem('living','Main lagi','home','Ke ruang santai','#95a787');
 $('actionTray').innerHTML=html;
}
function setRoom(name) {
 if(!rooms[name]) return;
 if(voicePhase!=='idle') stopVoice();
 roomName=name; $('room').dataset.room=name;
 const r=rooms[name]; $('roomLabel').innerHTML=icon(r.icon)+' '+r.label;
 $('actionTitle').textContent=r.title; $('actionSubtitle').textContent=r.subtitle;
 document.querySelectorAll('.room-tab').forEach(btn=>{const active=btn.dataset.room===name;btn.classList.toggle('active',active);if(active)btn.setAttribute('aria-current','page');else btn.removeAttribute('aria-current');});
 renderTray(); defaultSpeech();
}
function burst(kind='heart',count=7) {
 const fx=$('sceneFx'), w=fx.clientWidth||400,h=fx.clientHeight||406;
 for(let i=0;i<count;i++) {
  const e=document.createElement('span'); e.className='fx-particle'+(kind==='soap'?' bubble-particle':kind==='water'?' water-particle':'');
  e.style.left=(w/2-65+Math.random()*130)+'px';e.style.top=(h*.55-40+Math.random()*70)+'px';e.style.animationDelay=(i*.07)+'s';e.style.setProperty('--drift',(-60+Math.random()*120)+'px');
  if(kind==='heart')e.textContent='♥';else if(kind==='star')e.textContent='✦';else if(kind==='food')e.innerHTML=icon('fish');
  fx.appendChild(e);later(()=>e.remove(),2700);
 }
}
function animatePet(type,duration=1500) {
 const p=$('pet'); ['happy','eating','washing','playing'].forEach(c=>p.classList.remove(c));
 void p.offsetWidth; p.classList.add(type); actionBusy=true;
 clearTimeout(actionTimeout); actionTimeout=setTimeout(()=>{p.classList.remove(type);actionBusy=false;},duration);
}
function failed(reason) {
 const texts={sleeping:'Mimi sedang tidur. Tekan Bangun di kamar tidur dulu, ya.',full:'Mimi sudah kenyang. Yuk, main dulu!',coins:'Koin belum cukup. Main mini-game untuk menambah koin.',invalid:'Pilihan ini belum tersedia.'};
 toast(texts[reason]||'Coba lagi, ya.');if(reason==='sleeping')setRoom('bedroom');
}
function doAction(action,choice) {
 if(action==='games')return openArcade(); if(action==='shop')return openShop();
 if(rooms[action])return setRoom(action);
 if(actionBusy||voicePhase!=='idle')return;
 const r=E.care(state,action,choice);
 if(!r.ok)return failed(r.reason);
 if(action==='feed'){animatePet('eating');burst('food',3);say('Nyam nyam… enak banget!');}
 if(action==='wash'){animatePet('washing',2100);burst('soap',17);burst('water',25);say('Byuur! Sekarang aku wangi.');}
 if(action==='brush'){animatePet('happy');burst('star');say('Bulu lembut, hati juga lembut. ♡');}
 if(action==='pet'){animatePet('happy',950);burst('heart',6);say(['Purrr… aku sayang kamu!','Lagi dong, enak banget. ♡','Kamu teman terbaikku!'][Math.floor(Math.random()*3)]);}
 if(action==='play'){animatePet('playing',2200);burst('star');say('Hore! Tangkap bola benangnya!');}
 if(action==='sleep'){stopVoice();setRoom('bedroom');say(r.sleeping?'Zzz… sampai ketemu di mimpi.':'Huaaam… aku segar lagi!');}
 playTone(action==='wash'?'wash':'tap');update();renderTray();
 if(r.levels)toast(`Naik ke level ${E.level(state)}! Hadiah 25 koin. ✦`);
}
function focusDialog() { const el=$('modalContent').querySelector('button:not(:disabled), input')||$('closeModal'); el.focus(); }
function openModal(title,eyebrow,html,kind='') {
 if(voicePhase!=='idle')stopVoice();
 if($('modalOverlay').hidden)lastFocus=document.activeElement;
 modalKind=kind;$('modalTitle').textContent=title;$('modalEyebrow').textContent=eyebrow;$('modalContent').innerHTML=html;
 $('modalOverlay').hidden=false;document.body.style.overflow='hidden';focusDialog();
}
function closeModal(restore=true) { $('modalOverlay').hidden=true;modalKind='';if($('gameOverlay').hidden)document.body.style.overflow='';if(restore&&lastFocus?.focus)lastFocus.focus(); }
function openShop() {
 const colors={none:'#bd9974',bow:'#cd8c9d',leaf:'#8fab74',crown:'#c6a756',cap:'#83a9a1'};
 const html=`<p>Sentuhan kecil, gaya baru. Saldo kamu <b>${state.coins} koin</b>.</p><div class="shop-grid">${Object.entries(E.HATS).map(([k,h])=>`<button class="shop-item ${state.hat===k?'selected':''}" data-buy="${k}" style="--hat-color:${colors[k]}" ${!state.owned.includes(k)&&state.coins<h.price?'disabled':''}>${state.hat===k?`<span class="selected-marker">${icon('check')}</span>`:''}${icon(h.icon)}<b>${h.name}</b><small>${state.hat===k?'Sedang dipakai':state.owned.includes(k)?'Pakai':h.price+' '+icon('coin')}</small></button>`).join('')}</div>`;
 openModal('Lemari kecil Mimi','AKSESORI & CERITA',html,'shop');
}
function openGifts() {
 const claimed=state.gift===state.day;
 openModal('Ada hadiah untukmu!','SELAMAT DATANG KEMBALI',`<div class="gift-hero">${icon('gift')}<h3>40 koin, setiap hari.</h3><p>Terima kasih sudah menemani Mimi.</p><button class="large-button" data-ui="claim-gift" ${claimed?'disabled':''}>${icon(claimed?'check':'gift')}${claimed?'Sudah diambil hari ini':'Ambil 40 koin'}</button></div><div class="modal-missions"><h3>Misi sayang hari ini</h3>${missionRows()}<button class="large-button secondary" data-ui="claim-mission" ${!E.missionReady(state)||state.daily.claimed?'disabled':''}>${icon('coin')}${state.daily.claimed?'Hadiah misi sudah diambil':'Hadiah misi · 60 koin'}</button><p class="settings-note">Misi dan hadiah kembali tersedia setiap pergantian hari di perangkat ini.</p></div>`,'gift');
}
function openSettings() {
 openModal('Bikin terasa milikmu','PENGATURAN',`<div class="settings-field"><label for="nameInput">Nama teman kecilmu</label><input id="nameInput" maxlength="16" value="${safe(state.name)}" autocomplete="off" placeholder="Mimi"></div><button class="large-button" data-ui="save-name">${icon('check')}Simpan nama</button><button class="large-button secondary" data-ui="fullscreen">${icon('fullscreen')}Layar penuh</button><button class="large-button secondary" data-ui="help">${icon('help')}Cara bermain</button><p class="settings-note">Progres disimpan di browser dan perangkat ini. Menghapus data situs juga menghapus progres. Suara rekaman hanya diproses di perangkatmu.</p>`,'settings');
}
function openHelp() {
 openModal('Kenalan dengan Mimi','PANDUAN KECIL',`<p>Mimi adalah kucing virtual yang suka perhatianmu. Tidak ada iklan atau pembelian uang asli.</p><ol class="help-list"><li><b>Rawat setiap hari.</b> Pilih Makan, Mandi, atau Tidur. Biskuit dan perawatan dasar gratis.</li><li><b>Sentuh Mimi.</b> Ia suka dielus! Perawatan juga menambah XP, maksimal sekali setiap 10 detik.</li><li><b>Ajak ngobrol.</b> Izinkan mikrofon, bicara hingga 4 detik, lalu dengarkan Mimi mengulang dengan suara lucu. Tekan lagi untuk berhenti lebih cepat.</li><li><b>Main dan berdandan.</b> Mini-game memberi koin untuk camilan dan aksesori. Naik level memberi 25 koin.</li><li><b>Datang lagi.</b> Progres tersimpan otomatis. Kebutuhan berubah perlahan saat ditinggal; Mimi tetap aman.</li></ol><p>Tips: tambahkan halaman ini ke layar utama HP untuk membuka Mimi dengan cepat. Setelah sekali terbuka online, permainan juga bisa dibuka offline pada browser yang mendukungnya.</p><button class="large-button" data-ui="close">Ayo main ${icon('arrow')}</button>`,'help');
}
function openArcade() {
 if(state.sleeping)return failed('sleeping');
 openModal('Pilih petualanganmu','MAIN SEBENTAR, SENANG SEHARIAN',`<p>Koin hasil bermain bisa dipakai untuk camilan dan aksesori.</p><button class="arcade-choice" data-game="catch">${icon('fish')}<div><h3>Hujan ikan</h3><p>Geser keranjang. Tangkap ikan,<br>hindari bola benang.</p><small>Rekor: ${state.best.catch} ikan · 30 detik</small></div></button><button class="arcade-choice memory" data-game="memory">${icon('cards')}<div><h3>Pasangan mungil</h3><p>Cari 6 pasang gambar<br>sebelum waktu habis.</p><small>Rekor: ${state.best.memory} poin · 60 detik</small></div></button>`,'arcade');
}
function toggleSound() {state.sound=!state.sound;if(!state.sound)stopVoice();else playTone();update();}
$('soundButton').addEventListener('click',toggleSound);
$('settingsButton').addEventListener('click',openSettings);
$('giftButton').addEventListener('click',openGifts);
$('wardrobeButton').addEventListener('click',openShop);
$('arcadeButton').addEventListener('click',openArcade);
$('miniCard').addEventListener('click',openArcade);
$('helpButton').addEventListener('click',openHelp);
$('pet').addEventListener('click',()=>doAction('pet'));
$('missionButton').addEventListener('click',()=>{if(E.claimMission(state)){playTone('reward');toast('Misi selesai! Kamu mendapat 60 koin.');update();}else openGifts();});
document.querySelectorAll('.room-tab').forEach(btn=>btn.addEventListener('click',()=>{playTone();setRoom(btn.dataset.room);}));
$('actionTray').addEventListener('click',event=>{const btn=event.target.closest('[data-action]');if(btn)doAction(btn.dataset.action,btn.dataset.choice);});
$('closeModal').addEventListener('click',()=>closeModal());
$('modalOverlay').addEventListener('click',event=>{if(event.target===$('modalOverlay'))closeModal();});
$('modalContent').addEventListener('click',event=>{
 const buy=event.target.closest('[data-buy]');if(buy&&!buy.disabled){const r=E.shop(state,buy.dataset.buy);if(r.ok){playTone();update();openShop();toast(r.bought?'Aksesori baru! Langsung dipakai Mimi.':'Gaya Mimi sudah diganti.');}else failed(r.reason);return;}
 const g=event.target.closest('[data-game]');if(g){prepareGame(g.dataset.game);return;}
 const btn=event.target.closest('[data-ui]');if(!btn||btn.disabled)return;
 switch(btn.dataset.ui){
  case'close':closeModal();break;
  case'claim-gift':if(E.gift(state)){playTone('reward');update();openGifts();toast('+40 koin. Senang kamu kembali!');}break;
  case'claim-mission':if(E.claimMission(state)){playTone('reward');update();openGifts();toast('+60 koin. Terima kasih sudah merawat Mimi!');}break;
  case'save-name':{state.name=$('nameInput').value.trim().slice(0,16)||'Mimi';update();closeModal();say(`Halo, aku ${state.name}! ♡`);toast('Nama teman kecilmu disimpan.');break;}
  case'fullscreen':if(document.fullscreenElement){document.exitFullscreen?.().catch(()=>toast('Layar penuh tidak tersedia.'));}else if(document.documentElement.requestFullscreen){document.documentElement.requestFullscreen().catch(()=>toast('Browser ini belum mengizinkan layar penuh.'));}else toast('Gunakan menu browser → Tambahkan ke layar utama.');break;
  case'help':openHelp();break;
  case'voice-start':closeModal();startVoice();break;
  case'speak-text':speakText();break;
 }
});
function setVoice(phase) {
 voicePhase=phase; $('talkButton').classList.toggle('recording',phase==='recording');
 $('talkLabel').textContent=({idle:'Ajak ngobrol',permission:'Meminta izin…',recording:'Aku mendengarkan…',decoding:'Sebentar…',playing:'Mimi berbicara…'})[phase];
}
function stopVoice() {
 voiceToken++; clearTimeout(recordTimeout);recordTimeout=null;
 if(recorder&&recorder.state!=='inactive'){recorder.onstop=null;try{recorder.stop();}catch{}}
 recorder=null;if(mediaStream){mediaStream.getTracks().forEach(t=>t.stop());mediaStream=null;}
 if(voiceSource){try{voiceSource.stop();}catch{}voiceSource=null;}
 if(window.speechSynthesis)window.speechSynthesis.cancel();
 $('pet').classList.remove('talking');setVoice('idle');
}
function voiceFallback(reason) {
 openModal('Mimi mau mendengarkan','AJAK NGOBROL',`<p>${safe(reason)}</p><div class="settings-field"><label for="voiceText">Ketik sesuatu untuk ditirukan</label><input id="voiceText" maxlength="120" placeholder="Halo Mimi, ayo main!" autocomplete="off"></div><button class="large-button" data-ui="speak-text">${icon('mic')}Mimi, tirukan!</button><p class="settings-note">Suara teks mengikuti suara bahasa Indonesia yang tersedia di perangkatmu.</p>`,'voice');
}
async function startVoice() {
 if(state.sleeping)return failed('sleeping');
 if(!navigator.mediaDevices?.getUserMedia||!window.MediaRecorder)return voiceFallback('Mikrofon belum didukung di browser ini. Kamu bisa mencoba mode teks.');
 stopVoice();const token=voiceToken;setVoice('permission');
 let ac;
 try {ac=getAudio();if(!ac)throw new Error('audio');await ac.resume();}catch{setVoice('idle');return voiceFallback('Pemutar suara belum tersedia di browser ini. Coba mode teks.');}
 try {
  const stream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true},video:false});
  if(token!==voiceToken){stream.getTracks().forEach(t=>t.stop());return;}
  mediaStream=stream;const chunks=[];recorder=new MediaRecorder(stream);
  recorder.ondataavailable=e=>{if(e.data.size)chunks.push(e.data);};
  recorder.onerror=()=>{if(token===voiceToken){stopVoice();voiceFallback('Rekaman belum berhasil. Coba mode teks dulu, ya.');}};
  recorder.onstop=async()=>{
   stream.getTracks().forEach(t=>t.stop());mediaStream=null;recorder=null;clearTimeout(recordTimeout);
   if(token!==voiceToken)return;setVoice('decoding');
   try {
    const blob=new Blob(chunks,{type:chunks[0]?.type||'audio/webm'});
    if(blob.size<100)throw new Error('empty');
    const buffer=await ac.decodeAudioData(await blob.arrayBuffer());
    if(token!==voiceToken)return;
    await ac.resume();const source=ac.createBufferSource();source.buffer=buffer;source.playbackRate.value=1.38;source.connect(ac.destination);voiceSource=source;
    source.onended=()=>{if(token===voiceToken){voiceSource=null;setVoice('idle');$('pet').classList.remove('talking');say('Hehe, suaraku lucu, ya?');}};
    setVoice('playing');$('pet').classList.add('talking');say('Sekarang giliranku!',0);source.start();
    state.fun=Math.min(100,state.fun+6);update();
   }catch{if(token===voiceToken){stopVoice();voiceFallback('Rekaman ini belum bisa diputar. Gunakan mode teks atau coba Chrome terbaru.');}}
  };
  recorder.start();setVoice('recording');say('Aku dengarkan. Bilang sesuatu, yuk!',0);
  recordTimeout=setTimeout(()=>{if(recorder?.state==='recording')recorder.stop();},4000);
 }catch(err){if(token!==voiceToken)return;stopVoice();voiceFallback(err.name==='NotAllowedError'?'Izin mikrofon belum diberikan. Izinkan melalui pengaturan situs, atau gunakan mode teks.':'Mikrofon belum tersedia. Kamu bisa mencoba mode teks.');}
}
function speakText() {
 const value=$('voiceText')?.value.trim();if(!value)return toast('Tulis kalimat dulu, ya.');
 if(!window.speechSynthesis||!window.SpeechSynthesisUtterance)return toast('Suara teks tidak didukung browser ini.');
 closeModal();stopVoice();const token=voiceToken;
 const u=new SpeechSynthesisUtterance(value);u.lang='id-ID';u.pitch=1.7;u.rate=1.08;u.volume=.9;
 const v=speechSynthesis.getVoices().find(v=>v.lang.startsWith('id'));if(v)u.voice=v;
 setVoice('playing');$('pet').classList.add('talking');say(value,0);
 u.onend=()=>{if(token===voiceToken){stopVoice();defaultSpeech();}};
 u.onerror=()=>{if(token===voiceToken){stopVoice();toast('Suara teks belum tersedia. Coba mikrofon.');}};
 speechSynthesis.speak(u);recordTimeout=setTimeout(()=>{if(token===voiceToken){stopVoice();defaultSpeech();}},18000);
}
$('talkButton').addEventListener('click',()=>{
 if(voicePhase==='recording'){clearTimeout(recordTimeout);recorder?.stop();return;}
 if(voicePhase!=='idle'){stopVoice();defaultSpeech();return;}
 if(state.sleeping)return failed('sleeping');
 if(!navigator.mediaDevices?.getUserMedia||!window.MediaRecorder)return startVoice();
 openModal('Ngobrol sama Mimi','SUARA KECIL, TAWA BESAR',`<div class="record-prompt">${icon('mic')}<h3>Kamu bicara, Mimi menirukan.</h3><p>Rekam hingga 4 detik. Setelah itu Mimi mengulang suaramu dengan nada lucu.</p></div><button class="large-button" data-ui="voice-start">${icon('mic')}Mulai bicara</button><p class="settings-note">Izinkan mikrofon saat diminta. Rekaman hanya diputar di perangkat ini, tidak disimpan dan tidak dikirim ke server.</p>`,'voice');
});
function prepareGame(kind) {
 if(!['catch','memory'].includes(kind))return;
 closeModal(false);stopGame(false);
 lastFocus=$('arcadeButton');game={kind,phase:'ready'};
 $('gameOverlay').hidden=false;document.body.style.overflow='hidden';
 $('gameTitle').textContent=kind==='catch'?'Hujan ikan':'Pasangan mungil';
 $('gameScore').textContent=kind==='catch'?'0 ikan':'0 / 6 pasang';
 $('gameTimer').textContent=kind==='catch'?'30 dtk':'60 dtk';
 $('gameInstructions').textContent=kind==='catch'?'Geser keranjang dengan jari / mouse. Keyboard: ← →.':'Sentuh dua kartu. Ingat gambarnya, temukan pasangannya.';
 $('gameBoard').innerHTML=`<div class="game-start">${icon(kind==='catch'?'fish':'cards')}<h3>${kind==='catch'?'Siap panen ikan?':'Seberapa tajam ingatanmu?'}</h3><p>${kind==='catch'?'Tangkap ikan yang jatuh, hindari bola benang.<br>Setiap ikan bernilai 2 koin. Ronde 30 detik.':'Ada 6 pasang gambar yang bersembunyi.<br>Cari semuanya dalam 60 detik.'}</p><button class="large-button" data-start-game>Mulai bermain ${icon('arrow')}</button></div>`;
 $('gameBoard').querySelector('button').focus();
}
function startGame() {
 if(!game||game.phase!=='ready')return;
 const kind=game.kind;playTone();
 game={kind,phase:'running',time:kind==='catch'?30:60,last:performance.now(),score:0,keys:{},paused:false,flips:[],matched:0,moves:0,locked:false,spawn:.1,items:[],basket:195,flash:0};
 if(kind==='catch')initCatch();else initMemory();
 frame=requestAnimationFrame(gameLoop);
}
function initCatch() {
 $('gameBoard').innerHTML='<canvas class="catch-canvas" id="catchCanvas" width="390" height="350" tabindex="0" role="application" aria-label="Hujan ikan. Gunakan tombol panah kiri kanan atau geser keranjang dengan jari."></canvas>';
 const canvas=$('catchCanvas'),dpr=Math.min(window.devicePixelRatio||1,2);canvas.width=390*dpr;canvas.height=350*dpr;
 const ctx=canvas.getContext('2d');if(!ctx){stopGame();toast('Canvas tidak tersedia di browser ini. Coba mini-game kartu.');return;}
 ctx.scale(dpr,dpr);game.ctx=ctx;
 function move(event) {if(!game||game.kind!=='catch'||game.phase!=='running')return;const r=canvas.getBoundingClientRect();game.basket=Math.max(34,Math.min(356,(event.clientX-r.left)*390/r.width));}
 canvas.addEventListener('pointerdown',event=>{move(event);try{canvas.setPointerCapture(event.pointerId);}catch{}});
 canvas.addEventListener('pointermove',move);canvas.focus();
}
function rounded(ctx,x,y,w,h,r) {ctx.beginPath();ctx.moveTo(x+r,y);ctx.arcTo(x+w,y,x+w,y+h,r);ctx.arcTo(x+w,y+h,x,y+h,r);ctx.arcTo(x,y+h,x,y,r);ctx.arcTo(x,y,x+w,y,r);ctx.closePath();}
function drawFish(ctx,x,y,rotation) {
 ctx.save();ctx.translate(x,y);ctx.rotate(rotation);ctx.fillStyle='#cd9775';ctx.beginPath();ctx.ellipse(0,0,15,10,0,0,Math.PI*2);ctx.fill();ctx.beginPath();ctx.moveTo(-10,0);ctx.lineTo(-23,-10);ctx.lineTo(-23,10);ctx.closePath();ctx.fill();ctx.fillStyle='#f8e4c5';ctx.beginPath();ctx.ellipse(1,2,8,5,0,0,Math.PI*2);ctx.fill();ctx.fillStyle='#594c40';ctx.beginPath();ctx.arc(8,-2,2,0,Math.PI*2);ctx.fill();ctx.restore();
}
function drawYarn(ctx,x,y,rotation) {
 ctx.save();ctx.translate(x,y);ctx.rotate(rotation);ctx.fillStyle='#ac9aba';ctx.beginPath();ctx.arc(0,0,13,0,Math.PI*2);ctx.fill();ctx.strokeStyle='#d8c8e0';ctx.lineWidth=1.3;
 for(let i=-6;i<=6;i+=6){ctx.beginPath();ctx.moveTo(-10,i-3);ctx.quadraticCurveTo(2,i-7,11,i+3);ctx.stroke();}ctx.beginPath();ctx.moveTo(-6,-10);ctx.quadraticCurveTo(-3,0,7,10);ctx.stroke();ctx.restore();
}
function drawCatch(g,dt) {
 const c=g.ctx;if(!c)return;
 const W=390,H=350;
 g.spawn-=dt;g.flash=Math.max(0,g.flash-dt);
 if(g.keys.ArrowLeft||g.keys.a)g.basket=Math.max(34,g.basket-dt*340);
 if(g.keys.ArrowRight||g.keys.d)g.basket=Math.min(356,g.basket+dt*340);
 if(g.spawn<=0){g.items.push({x:28+Math.random()*(W-56),y:-20,v:96+Math.random()*55+(30-g.time)*1.7,bad:Math.random()<.23,r:Math.random()*.7-.35});g.spawn=.5+Math.random()*.25;}
 for(let i=g.items.length-1;i>=0;i--){const o=g.items[i];o.y+=dt*o.v;
  if(o.y>=285&&o.y<=323&&Math.abs(o.x-g.basket)<43){g.items.splice(i,1);if(o.bad){g.score=Math.max(0,g.score-2);g.flash=.3;playTone('bad');}else{g.score++;playTone();}}
  else if(o.y>H+22)g.items.splice(i,1);
 }
 c.clearRect(0,0,W,H);c.fillStyle='#e0e9d8';c.fillRect(0,0,W,H);
 c.fillStyle='#d1dfc7';c.beginPath();c.moveTo(0,250);c.quadraticCurveTo(70,203,156,256);c.quadraticCurveTo(286,195,W,258);c.lineTo(W,H);c.lineTo(0,H);c.fill();
 c.fillStyle='#c0d2b4';c.fillRect(0,325,W,25);
 c.fillStyle='#f8f2d4';c.beginPath();c.arc(329,53,24,0,Math.PI*2);c.fill();
 c.fillStyle='#f9faeea0';[[50,40],[183,65]].forEach(([x,y])=>{c.beginPath();c.ellipse(x,y,27,9,0,0,Math.PI*2);c.ellipse(x-10,y-5,15,10,0,0,Math.PI*2);c.fill();});
 c.fillStyle='#99b18b';for(let i=0;i<12;i++){const x=i*35;c.beginPath();c.moveTo(x,333);c.quadraticCurveTo(x-6,318,x+3,316);c.quadraticCurveTo(x+9,319,x+6,333);c.fill();}
 for(const o of g.items){if(o.bad)drawYarn(c,o.x,o.y,o.r);else drawFish(c,o.x,o.y,o.r);}
 const x=g.basket;c.fillStyle=g.flash?'#d38e88':'#c09a6c';c.beginPath();c.moveTo(x-39,294);c.lineTo(x+39,294);c.lineTo(x+29,329);c.quadraticCurveTo(x,338,x-29,329);c.closePath();c.fill();
 c.strokeStyle=g.flash?'#ad7270':'#e4c59b';c.lineWidth=3;for(let y=303;y<328;y+=8){c.beginPath();c.moveTo(x-31,y);c.lineTo(x+31,y);c.stroke();}for(let j=-22;j<28;j+=11){c.beginPath();c.moveTo(x+j,298);c.lineTo(x+j*.75,330);c.stroke();}
 c.fillStyle='#e5c39a';rounded(c,x-43,289,86,10,5);c.fill();
 $('gameScore').textContent=`${g.score} ikan`;
}
const faces=['fish','paw','heart','moon','leaf','star'];
function initMemory() {
 const cards=[...faces,...faces];for(let i=cards.length-1;i>0;i--){const j=Math.floor(Math.random()*(i+1));[cards[i],cards[j]]=[cards[j],cards[i]];}
 game.cards=cards;game.found=new Set();
 $('gameBoard').innerHTML=`<div class="memory-grid">${cards.map((_,i)=>`<button class="memory-tile" data-tile="${i}" aria-label="Kartu ${i+1}, tertutup">${icon('paw')}</button>`).join('')}</div>`;
 $('gameBoard').querySelector('button').focus();
}
function flipCard(i) {
 const g=game;if(!g||g.kind!=='memory'||g.phase!=='running'||g.paused||g.locked||g.found.has(i)||g.flips.includes(i))return;
 const tile=$('gameBoard').querySelector(`[data-tile="${i}"]`);if(!tile)return;
 g.flips.push(i);tile.classList.add('face-up');tile.innerHTML=icon(g.cards[i]);tile.setAttribute('aria-label',`Kartu ${i+1}, ${g.cards[i]}`);playTone();
 if(g.flips.length!==2)return;
 g.moves++;const[a,b]=g.flips;
 if(g.cards[a]===g.cards[b]){g.found.add(a);g.found.add(b);g.matched++;g.flips=[];[a,b].forEach(n=>{const t=$('gameBoard').querySelector(`[data-tile="${n}"]`);t.classList.add('matched');t.disabled=true;t.setAttribute('aria-label','Pasangan ditemukan');});playTone('reward');$('gameScore').textContent=`${g.matched} / 6 pasang`;if(g.matched===6)finishGame();}
 else {g.locked=true;later(()=>{if(game!==g||g.phase!=='running')return;[a,b].forEach(n=>{const t=$('gameBoard').querySelector(`[data-tile="${n}"]`);if(t){t.classList.remove('face-up');t.innerHTML=icon('paw');t.setAttribute('aria-label',`Kartu ${n+1}, tertutup`);}});g.flips=[];g.locked=false;},760);}
}
function gameLoop(time) {
 const g=game;if(!g||g.phase!=='running'||g.paused)return;
 const dt=Math.min(.06,Math.max(0,(time-g.last)/1000));g.last=time;g.time=Math.max(0,g.time-dt);
 $('gameTimer').textContent=`${Math.ceil(g.time)} dtk`;
 if(g.kind==='catch')drawCatch(g,dt);
 if(g.time<=0){finishGame();return;}
 frame=requestAnimationFrame(gameLoop);
}
function finishGame() {
 const g=game;if(!g||g.phase!=='running')return;g.phase='finished';cancelAnimationFrame(frame);
 const score=g.kind==='catch'?g.score:g.matched*25+(g.matched===6?Math.floor(g.time*2):0);
 const r=E.gameReward(state,g.kind,score);update();playTone('reward');
 $('gameBoard').innerHTML=`<div class="game-start">${icon('star')}<h3>${g.kind==='memory'&&g.matched<6?'Usaha yang bagus!':'Keren banget, teman!'}</h3><p>${g.kind==='catch'?`Kamu menangkap ${score} ikan.`:`Kamu menemukan ${g.matched} dari 6 pasang. Skor: ${score}.`}${r.best?'<br>✦ Rekor baru!':''}</p><div class="reward-line">+${r.coins} ${icon('coin')}</div><p>+25 XP · Mimi makin bahagia</p><button class="large-button" data-replay>Main lagi ${icon('arrow')}</button><button class="large-button secondary" data-back-home>Kembali ke Mimi</button></div>`;
 $('gameInstructions').textContent='Hadiah sudah tersimpan. Terima kasih sudah bermain!';
 $('gameBoard').querySelector('button').focus();
 if(r.levels)toast(`Naik ke level ${E.level(state)}! Bonus 25 koin.`);
}
function stopGame(hide=true) {cancelAnimationFrame(frame);clearTimeout(gameTimeout);game=null;if(hide){$('gameOverlay').hidden=true;document.body.style.overflow='';if(lastFocus?.focus)lastFocus.focus();}}
$('closeGame').addEventListener('click',()=>{const unfinished=game?.phase==='running';stopGame();if(unfinished)toast('Ronde ditutup. Selesaikan ronde untuk mendapatkan koin.');});
$('gameBoard').addEventListener('click',event=>{
 if(event.target.closest('[data-start-game]'))startGame();
 else if(event.target.closest('[data-replay]'))prepareGame(game.kind);
 else if(event.target.closest('[data-back-home]'))stopGame();
 else {const tile=event.target.closest('[data-tile]');if(tile)flipCard(Number(tile.dataset.tile));}
});
document.addEventListener('keydown',event=>{
 if(event.key==='Escape') {if(!$('modalOverlay').hidden)closeModal();else if(!$('gameOverlay').hidden)stopGame();else if(voicePhase!=='idle')stopVoice();return;}
 if(game?.phase==='running'&&game.kind==='catch'&&['ArrowLeft','ArrowRight','a','d'].includes(event.key)){event.preventDefault();game.keys[event.key]=true;}
 if(event.key==='Tab') {
  const active=!$('modalOverlay').hidden?$('modalOverlay'):!$('gameOverlay').hidden?$('gameOverlay'):null;
  if(!active)return;
  const nodes=[...active.querySelectorAll('button:not(:disabled), input, [tabindex="0"]')].filter(el=>!el.hidden);
  if(!nodes.length)return;
  if(event.shiftKey&&document.activeElement===nodes[0]){event.preventDefault();nodes[nodes.length-1].focus();}
  else if(!event.shiftKey&&document.activeElement===nodes[nodes.length-1]){event.preventDefault();nodes[0].focus();}
 }
});
document.addEventListener('keyup',event=>{if(game?.keys)game.keys[event.key]=false;});
window.addEventListener('blur',()=>{if(game?.keys)game.keys={};});
document.addEventListener('visibilitychange',()=>{
 if(document.hidden){stopVoice();E.advance(state);persist();if(game?.phase==='running'){game.paused=true;game.keys={};cancelAnimationFrame(frame);}}
 else {const slept=state.sleeping;E.advance(state);update();if(slept&&!state.sleeping){renderTray();say('Tidurku nyenyak! Yuk, main lagi.');}if(game?.phase==='running'){game.paused=false;game.last=performance.now();frame=requestAnimationFrame(gameLoop);}}
});
window.addEventListener('pagehide',()=>{stopVoice();E.advance(state);persist();});
let ticks=0;
setInterval(()=>{
 if(document.hidden)return;
 const slept=state.sleeping,oldDay=state.day;E.advance(state);
 if(slept&&!state.sleeping){renderTray();say('Pagi! Energi penuh, siap main lagi.');}
 update();ticks++;if(ticks%20===0&&voicePhase==='idle'&&!actionBusy)defaultSpeech();
 if(oldDay!==state.day&&modalKind==='gift')openGifts();
},1000);
setRoom(roomName);update();persist();
if(!state.sleeping)say(`Halo! Aku ${state.name}. Mau main bareng? ♡`,7000);
if('serviceWorker' in navigator && location.protocol==='https:'){
 window.addEventListener('load',()=>navigator.serviceWorker.register('./sw.js').catch(()=>{}));
}
})();
