'use strict';
// Incremental SHA-256: bounded memory even for multi-gigabyte files.
class Sha256 {
 constructor(){this.h=new Uint32Array([0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19]);this.b=new Uint8Array(64);this.n=0;this.bytes=0;}
 block(b){const k=[0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2];
 const r=(v,n)=>(v>>>n)|(v<<(32-n));const w=new Uint32Array(64);for(let i=0;i<16;i++)w[i]=((b[i*4]<<24)|(b[i*4+1]<<16)|(b[i*4+2]<<8)|b[i*4+3])>>>0;
 for(let i=16;i<64;i++){const x=w[i-15],y=w[i-2];w[i]=(w[i-16]+(r(x,7)^r(x,18)^(x>>>3))+w[i-7]+(r(y,17)^r(y,19)^(y>>>10)))>>>0;}
 let [a,c,d,e,f,g,h,j]=this.h;for(let i=0;i<64;i++){const t=(j+(r(f,6)^r(f,11)^r(f,25))+((f&g)^(~f&h))+k[i]+w[i])>>>0;const u=((r(a,2)^r(a,13)^r(a,22))+((a&c)^(a&d)^(c&d)))>>>0;j=h;h=g;g=f;f=(e+t)>>>0;e=d;d=c;c=a;a=(t+u)>>>0;}[a,c,d,e,f,g,h,j].forEach((v,i)=>this.h[i]=(this.h[i]+v)>>>0);
 }
 update(data){this.bytes+=data.length;let i=0;while(i<data.length){const n=Math.min(64-this.n,data.length-i);this.b.set(data.subarray(i,i+n),this.n);this.n+=n;i+=n;if(this.n===64){this.block(this.b);this.n=0;}}return this;}
 hex(){const length=this.bytes;this.update(new Uint8Array([128]));while(this.n!==56)this.update(new Uint8Array([0]));const tail=new Uint8Array(8),view=new DataView(tail.buffer);view.setUint32(0,Math.floor(length/0x20000000));view.setUint32(4,(length*8)>>>0);this.update(tail);return Array.from(this.h,v=>v.toString(16).padStart(8,'0')).join('');}
}
if(typeof module!=='undefined')module.exports={Sha256};
if(typeof document!=='undefined'){
 const $=id=>document.getElementById(id);let token='',busy=false,abort=null,cancelled=false,current=null;
 history.replaceState(null,'',location.pathname);
 const status=t=>$('status').textContent=t;
 const size=b=>b<1024?b+' Б':b<1048576?Math.round(b/1024)+' КБ':b<1073741824?(b/1048576).toFixed(1)+' МБ':(b/1073741824).toFixed(2)+' ГБ';
 const time=s=>s>=60?Math.floor(s/60)+' мин '+Math.round(s%60)+' с':Math.max(1,Math.round(s))+' с';
 async function api(path,method='GET',body=null,headers={}){const response=await fetch(path,{method,body,headers:{...headers,...(token?{Authorization:'Bearer '+token}:{})},signal:abort?.signal,cache:'no-store'});const value=await response.json();if(!response.ok)throw Error(value.error||'Ошибка');return value;}
 const pause=(ms=1000)=>new Promise(r=>setTimeout(r,ms));
 function device(){const u=navigator.userAgent;return /Android/.test(u)?'Телефон (браузер)':/iPhone|iPad/.test(u)?'iPhone (браузер)':/Windows/.test(u)?'Компьютер Windows':/Mac/.test(u)?'Mac':'Браузер';}
 // Подключение сразу при открытии страницы; без связи — тихий повтор.
 async function connect(){for(;;){try{const p=await api('/pair','POST',JSON.stringify({name:device(),code:''}));for(let i=0;i<60;i++){const s=await api('/pair/'+p.id);if(s.token){token=s.token;$('session').hidden=false;status('Подключено');await list();return;}await pause();}}catch(e){status('Нет связи с устройством, повторяю… Откройте «Передачу» на телефоне или ТВ.');await pause(3000);}}}
 const chosen=()=>[...$('pick').files,...$('folder').files];
 function showChosen(){const f=chosen();$('chosen').textContent=f.length?'Выбрано: '+f.length+' · '+size(f.reduce((a,x)=>a+x.size,0)):'Ничего не выбрано';$('send').disabled=busy||!f.length;}
 $('pick').onchange=showChosen;$('folder').onchange=showChosen;
 async function list(){const data=await api('/files');$('files').replaceChildren();if(!data.files.length){const p=document.createElement('p');p.textContent='На устройстве ничего не выбрано для отправки.';$('files').append(p);}for(const f of data.files){const button=document.createElement('button');button.textContent='Скачать '+f.name+' · '+size(f.size);button.onclick=async()=>{try{const ticket=await api('/ticket/'+f.id,'POST');const a=document.createElement('a');a.href=ticket.url;a.download=f.name.replaceAll('/','_');document.body.append(a);a.click();a.remove();status('Скачивание: '+f.name);}catch(e){status(e.message);}};$('files').append(button);}}
 $('refresh').onclick=()=>list().catch(e=>status(e.message));
 // Блоки по 1 МиБ; после обрыва связи отправка сама продолжается с подтверждённого места.
 async function upload(c,shown){let s=await api('/upload/'+c.id);if(s.done)return;let offset=s.offset,failures=0;
  while(offset<c.file.size){if(cancelled)throw Error('cancel');
   try{const chunk=new Uint8Array(await c.file.slice(offset,offset+1048576).arrayBuffer());const hash=new Sha256().update(chunk).hex();
    const r=await api('/upload/'+c.id,'PUT',chunk,{'X-Offset':String(offset),'X-SHA256':hash});offset=r.offset;failures=0;shown(offset);}
   catch(e){if(cancelled)throw Error('cancel');if(++failures>30)throw Error('Связь потеряна. Проверьте Wi-Fi и отправьте снова.');status('Связь прервалась — продолжаю…');await pause(2000);try{offset=(await api('/upload/'+c.id)).offset;}catch(_){}}}
  await api('/upload/'+c.id,'POST');}
 $('send').onclick=async()=>{const files=chosen();if(busy||!files.length)return;busy=true;cancelled=false;abort=new AbortController();showChosen();$('job').hidden=false;$('cancel').hidden=false;$('progress').value=0;
  const total=files.reduce((a,f)=>a+f.size,0);let before=0;const started=Date.now();
  try{const batch=(await api('/batch','POST')).id;
   for(let i=0;i<files.length;i++){const file=files[i],name=file.webkitRelativePath||file.name,label=(files.length>1?'Файл '+(i+1)+' из '+files.length+': ':'')+name;
    $('jobname').textContent='Подготовка — '+label;const hash=new Sha256();for(let o=0;o<file.size;o+=1048576){if(cancelled)throw Error('cancel');hash.update(new Uint8Array(await file.slice(o,o+1048576).arrayBuffer()));}
    const offer=await api('/offer','POST',JSON.stringify({name,size:file.size,sha256:hash.hex(),batch}));current={id:offer.id};$('jobname').textContent=label;status('Отправка…');
    await upload({id:offer.id,file},sent=>{const all=before+sent,sec=Math.max((Date.now()-started)/1000,0.5),speed=all/sec;$('progress').value=all/Math.max(total,1);
     $('jobinfo').textContent=Math.floor(all*100/Math.max(total,1))+'% · '+size(all)+' из '+size(total)+' · '+size(speed)+'/с · осталось '+time((total-all)/Math.max(speed,1));});
    before+=file.size;current=null;}
   $('progress').value=1;$('jobname').textContent='Готово';$('jobinfo').textContent='Отправлено: '+files.length+' · '+size(total);status('Готово. Файлы — в «Загрузки/Передача» на устройстве.');$('pick').value='';$('folder').value='';}
  catch(e){status(cancelled?'Отправка отменена':e.message);$('jobname').textContent=cancelled?'Отменено':'Ошибка';}
  finally{busy=false;abort=null;current=null;$('cancel').hidden=true;showChosen();}};
 $('cancel').onclick=async()=>{cancelled=true;abort?.abort();const c=current;current=null;if(c){try{await fetch('/upload/'+c.id,{method:'DELETE',headers:{Authorization:'Bearer '+token}});}catch(_){}}};
 showChosen();connect();
}
