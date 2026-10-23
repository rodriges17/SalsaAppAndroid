import {test, before, after} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire(import.meta.url);
const {chromium}=process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES?require(process.env.CODEX_PRIMARY_RUNTIME_NODE_MODULES+'/playwright'):require('playwright');
const assets=new URL('../app/src/main/assets/',import.meta.url);
let browser,server,origin;
before(async()=>{
 server=createServer(async(req,res)=>{try{const path=decodeURI(req.url.split('?')[0]);const file=path==='/fixture.mp4'?new URL('./fixtures/video.mp4',import.meta.url):path==='/assets/old-upgrade-sw.js'?new URL('./fixtures/old-upgrade-sw.js',import.meta.url):new URL('.'+(path.startsWith('/assets/')?path.slice(7):path),assets);const data=await readFile(file);const type=path.endsWith('.js')?'text/javascript':path.endsWith('.css')?'text/css':path.endsWith('.mp4')?'video/mp4':path.endsWith('.png')?'image/png':'text/html';res.writeHead(200,{'Content-Type':type});res.end(data);}catch{res.writeHead(404);res.end();}}).listen(0,'127.0.0.1');
 await new Promise(r=>server.once('listening',r));origin=`http://127.0.0.1:${server.address().port}`;
 browser=await chromium.launch({executablePath:process.env.CHROMIUM_EXECUTABLE,headless:true,args:['--no-sandbox']});
});
after(async()=>{await browser?.close();await new Promise(r=>server?.close(r));});
async function setup(){const context=await browser.newContext();const page=await context.newPage();await page.addInitScript(()=>{
 window.nativeReady=false;window.replies=new Map();window.NativeUI={ready(){window.nativeReady=true;},result(id,json){const reply=window.replies.get(id);if(reply){window.replies.delete(id);reply(JSON.parse(json));}},progress(){},home(){}};
 window.NativeMedia={data:new Map(),begin(){const t=crypto.randomUUID();this.data.set(t,[]);return t;},chunk(t,b){this.data.get(t).push(b);return true;},finish(){return true;},abort(t){this.data.delete(t);}};
 window.AndroidPlatform={rotate(){},keepAwake(){},fullscreen(){}};
 window.AndroidDownloads={parts:[],start(name){this.name=name;this.parts=[];return true;},chunk(b){this.parts.push(b);return true;},finish(type){this.type=type;return true;},abort(){}};
 const fs=JSON.parse(localStorage.getItem('mock-native-fs')||'{"files":{},"dirs":[""]}');
 const save=()=>localStorage.setItem('mock-native-fs',JSON.stringify(fs));
 const decode=s=>Uint8Array.from(atob(s),c=>c.charCodeAt(0));const encode=b=>{let s='';for(const c of b)s+=String.fromCharCode(c);return btoa(s);};
 const writers=new Map();
 window.AndroidFiles={pick(){setTimeout(()=>window.AndroidFSResult({root:'grant',path:'',name:'Android documents',kind:'directory'}));},call(raw){const a=JSON.parse(raw);let value;try{switch(a.op){case'permission':value='granted';break;case'child':{const path=[a.path,a.name].filter(Boolean).join('/');if(a.directory){if(!fs.dirs.includes(path)){if(!a.create)throw Error('NotFoundError');fs.dirs.push(path);}}else if(!(path in fs.files)){if(!a.create)throw Error('NotFoundError');fs.files[path]={data:'',time:1};}save();value={root:a.root,path,name:a.name,kind:a.directory?'directory':'file'};break;}case'list':value=[...fs.dirs,...Object.keys(fs.files)].filter(p=>p!==a.path&&p.startsWith(a.path?a.path+'/':'')&&!p.slice(a.path?a.path.length+1:0).includes('/')).map(p=>p.split('/').at(-1));break;case'stat':{const f=fs.files[a.path];if(!f)throw Error('NotFoundError');value={size:decode(f.data).length,lastModified:f.time,name:a.path.split('/').at(-1),url:'https://appassets.androidplatform.net/mock/'+encodeURIComponent(a.path)};break;}case'begin':value=crypto.randomUUID();writers.set(value,{path:a.path,parts:[]});break;case'write':writers.get(a.token).parts.push(decode(a.data));value=true;break;case'close':{const w=writers.get(a.token);const size=w.parts.reduce((s,p)=>s+p.length,0);const out=new Uint8Array(size);let p=0;w.parts.forEach(b=>{out.set(b,p);p+=b.length;});fs.files[w.path]={data:encode(out),time:Date.now()};save();writers.delete(a.token);value=true;break;}case'abort':writers.delete(a.token);value=true;break;case'remove':{const path=[a.path,a.name].filter(Boolean).join('/');if(!fs.files[path])throw Error('NotFoundError');delete fs.files[path];save();value=true;break;}default:throw Error('Unknown operation');}return JSON.stringify({value});}catch(e){return JSON.stringify({error:e.message,name:e.message==='NotFoundError'?'NotFoundError':'InvalidStateError'});}}};
 const fetchOriginal=window.fetch.bind(window);window.fetch=async(input,options)=>{const u=String(input);if(u.startsWith('https://appassets.androidplatform.net/mock/')){const f=fs.files[decodeURIComponent(u.split('/mock/')[1])];return new Response(f?decode(f.data):null,{status:f?200:404,headers:{'Content-Type':'application/octet-stream'}});}return fetchOriginal(input,options);};
 });await page.goto(origin+'/index.html');await page.waitForFunction(()=>window.nativeReady);return{context,page};}
async function rpc(page,op,args={}){const reply=await page.evaluate(({op,args})=>new Promise(resolve=>{const id=Math.floor(Math.random()*1e9);window.replies.set(id,resolve);NativeModel.request(id,op,args);}),{op,args});if(reply.error)throw Error(reply.error);return reply.value;}
async function add(page,session,name='Turn'){return rpc(page,'add',{token:session.token,url:origin+'/fixture.mp4',name,type:'video/mp4',duration:1,width:160,height:240});}
async function create(page,title='Drehung'){const d=await rpc(page,'edit');const videos=await add(page,d);return rpc(page,'save',{token:d.token,title,description:'Schritte und Zählzeit',status:'Am Üben',tags:['Drehung'],videos});}

test('native repository shares existing PWA figures, preserves edits and checks original media bytes',async()=>{const{page,context}=await setup();try{
 const old=await page.evaluate(async()=>{const s=await import('./storage.js');const blob=await(await fetch('/fixture.mp4')).blob();const f=await s.saveFigure({id:crypto.randomUUID(),title:'PWA Bestand',description:'',status:'Sitzt',tags:['Bestand']},[{id:crypto.randomUUID(),blob,name:'Original',duration:1,confirmed:true}]);return f.id;});
 assert.equal((await rpc(page,'list'))[0].id,old);
 const d=await rpc(page,'edit',{id:old});let videos=await add(page,d,'zweites');videos=await add(page,d,'drittes');
 const retain=[{...videos[2],name:'Erstes umbenannt'},videos[0]];
 videos=await rpc(page,'add',{token:d.token,url:origin+'/fixture.mp4',name:'viertes',type:'video/mp4',duration:1,keep:retain});
 assert.deepEqual(videos.map(v=>v.name),['Erstes umbenannt','Original','viertes']);
 const f=await rpc(page,'save',{token:d.token,title:'Turn',description:'Zählzeit',status:'Sitzt',tags:['Anfänger'],videos});
 assert.equal((await rpc(page,'list',{query:'zählzeit',tag:'Anfänger',status:'Sitzt'})).length,1);
 const media=await rpc(page,'media',{id:f.id,video:f.videoIds[0]});const bytes=await page.evaluate(t=>NativeMedia.data.get(t).map(b=>Array.from(atob(b),c=>c.charCodeAt(0))).flat(),media.token);assert.deepEqual(Buffer.from(bytes),await readFile(new URL('./fixtures/video.mp4',import.meta.url)));
 await page.reload();await page.waitForFunction(()=>window.nativeReady);assert.equal((await rpc(page,'list'))[0].videoIds.length,3);
 }finally{await context.close();}});

test('native ZIP import requires confirmation and full backup restores learning status with explicit plan',async()=>{const{page,context}=await setup();try{
 const f=await create(page);await rpc(page,'packageExport',{id:f.id,videos:f.videoIds});
 const zip=await page.evaluate(()=>{const bytes=AndroidDownloads.parts.flatMap(b=>Array.from(atob(b),c=>c.charCodeAt(0)));return URL.createObjectURL(new Blob([Uint8Array.from(bytes)],{type:'application/zip'}));});
 const draft=await rpc(page,'packageImport',{url:zip});assert.equal(draft.figure.status,'Neu');assert.equal(draft.videos[0].confirmed,false);
 await assert.rejects(()=>rpc(page,'save',{token:draft.token,title:'Kopie',description:'',status:'Neu',tags:[],videos:draft.videos}),/bestätigen/);
 const checked=await rpc(page,'confirm',{token:draft.token,video:draft.videos[0].id,duration:1,width:160,height:240});await rpc(page,'save',{token:draft.token,title:'Kopie',description:'',status:'Neu',tags:[],videos:checked});assert.equal((await rpc(page,'packageImport',{url:zip})).duplicate,true);
 let backup=await rpc(page,'backup',{action:'startCreate'});assert.equal(backup.view,'create-ready');backup=await rpc(page,'backup',{action:'create'});assert.equal(backup.view,'created');
 const parts=[];for(let i=0;i<backup.data.parts.length;i++){await rpc(page,'backup',{action:'download',index:i});parts.push(await page.evaluate(()=>{const bytes=AndroidDownloads.parts.flatMap(b=>Array.from(atob(b),c=>c.charCodeAt(0)));return{name:AndroidDownloads.name,url:URL.createObjectURL(new Blob([Uint8Array.from(bytes)]))};}));}
 for(const local of await rpc(page,'list'))await rpc(page,'delete',{id:local.id});
 await rpc(page,'backup',{action:'startRestore'});await rpc(page,'backup',{action:'files',files:parts});backup=await rpc(page,'backup',{action:'checkRestore'});assert.equal(backup.view,'restore-plan');assert.equal((await rpc(page,'list')).length,0);
 await rpc(page,'backup',{action:'restore'});const restored=await rpc(page,'list');assert.equal(restored.length,2);assert.equal(restored.find(f=>f.title==='Drehung').status,'Am Üben');
 }finally{await context.close();}});

test('Android document handles survive IndexedDB reload and transfer keeps the original until explicit deletion',async()=>{const{page,context}=await setup();try{
 const f=await create(page);const settings=await rpc(page,'folder',{action:'choose'});const folder=settings.activeFolder;assert.ok(folder);
 const copy=await rpc(page,'transfer',{id:f.id,target:folder});assert.equal(copy.storageFolder,folder);assert.equal((await rpc(page,'list')).length,2);
 await page.reload();await page.waitForFunction(()=>window.nativeReady);const all=await rpc(page,'list');assert.equal(all.length,2);assert.ok(!all.find(f=>f.storageFolder)?.storageProblem);
 const original=await rpc(page,'transfer',{id:copy.id,storageFolder:folder,target:null});assert.ok(original.id!==f.id);assert.equal((await rpc(page,'list')).length,3);
 await rpc(page,'delete',{id:copy.id,storageFolder:folder});assert.equal((await rpc(page,'list')).length,2);
 }finally{await context.close();}});

test('native HTTPS adapter streams uploads and responses and propagates cancellation without browser CORS',async()=>{const{page,context}=await setup();try{
 const result=await page.evaluate(async()=>{
  const calls=[];const canceled=[];
  window.AndroidNetwork={start(id,url,method,headers,body,token){calls.push({id,url,method,headers:JSON.parse(headers),body,token,parts:token?NativeMedia.data.get(token).length:0});if(url.endsWith('/slow'))return;const data=new Blob([JSON.stringify({ok:true})],{type:'application/json'});setTimeout(()=>AndroidNetworkResult(id,{status:200,type:'application/json',url:URL.createObjectURL(data)}));},cancel(id){canceled.push(id);}};
  await import('./android-network.js?native-test');
  const response=await fetch('https://tzaprobvauikssnpiucl.supabase.co/functions/v1/cloud',{method:'POST',headers:{apikey:'public-test-key','Content-Type':'application/json'},body:JSON.stringify({action:'state',payload:{}})});const json=await response.json();
  await fetch('https://tzaprobvauikssnpiucl.supabase.co/storage/v1/object/salsa-cloud/test',{method:'POST',headers:{authorization:'Bearer test'},body:new Blob([new Uint8Array(150000)],{type:'video/mp4'})});
  const controller=new AbortController();const pending=fetch('https://tzaprobvauikssnpiucl.supabase.co/auth/v1/slow',{signal:controller.signal});controller.abort();let error;try{await pending;}catch(e){error=e.name;}
  return {json,calls,canceled,error,remaining:NativeMedia.data.size};
 });
 assert.equal(result.json.ok,true);assert.equal(result.calls[0].method,'POST');assert.equal(JSON.parse(result.calls[0].body).action,'state');assert.equal(result.calls[1].parts,4);assert.equal(result.error,'AbortError');assert.equal(result.canceled.length,1);assert.equal(result.remaining,0);
 }finally{await context.close();}});

test('native cloud upload preserves the server reservation/finalize/commit contract and abort releases reservations',async()=>{const{page,context}=await setup();try{
 const actions=[],uploaded=[];let stall=false,started=false;
 await page.route('https://tzaprobvauikssnpiucl.supabase.co/**',async route=>{
  const request=route.request(),url=new URL(request.url());const headers={'Access-Control-Allow-Origin':'*','Access-Control-Allow-Headers':'authorization,apikey,content-type','Access-Control-Allow-Methods':'POST,OPTIONS'};
  if(request.method()==='OPTIONS'){await route.fulfill({status:204,headers});return;}
  let result={ok:true};
  if(url.pathname==='/auth/v1/verify')result={access_token:'test-token',refresh_token:'test-refresh',expires_in:3600};
  else if(url.pathname==='/functions/v1/cloud'){
   const {action,payload}=request.postDataJSON();actions.push({action,payload});
   if(action==='reserve')result={job:'job-1',media:payload.videos.map((_,i)=>({id:`media-${i}`,path:`user/video-${i}`}))};
   if(action==='commit')result={revision:'revision-1'};
  }else if(url.pathname.startsWith('/storage/v1/object/salsa-cloud/')){
   started=true;uploaded.push(request.postDataBuffer());if(stall)await new Promise(resolve=>setTimeout(resolve,150));
  }
  try{await route.fulfill({status:200,headers,contentType:'application/json',body:JSON.stringify(result)});}catch{/* canceled request */}
 });
 await rpc(page,'login',{link:'https://rodriges17.github.io/SalsaAppCloud/?token_hash=unit-test-token-hash-1234567890123456&type=magiclink'});
 const f=await create(page);await rpc(page,'cloudUpload',{id:f.id,videos:f.videoIds,library:'library-1',allowed:true});
 assert.deepEqual(actions.map(a=>a.action),['reserve','finalize','commit','attach']);assert.equal(actions[2].payload.public_allowed,true);assert.deepEqual(uploaded[0],await readFile(new URL('./fixtures/video.mp4',import.meta.url)));
 actions.length=0;stall=true;started=false;const pending=rpc(page,'cloudUpload',{id:f.id,videos:f.videoIds,allowed:false});pending.catch(()=>{});
 while(!started)await new Promise(resolve=>setTimeout(resolve,5));await rpc(page,'cancel');await assert.rejects(()=>pending);assert.ok(actions.some(a=>a.action==='abort'&&a.payload.job==='job-1'));
 }finally{await context.close();}});

test('canceling a native ZIP import leaves the saved library untouched',async()=>{const{page,context}=await setup();try{
 await create(page);let started=false;
 await page.route(origin+'/slow-package.zip',async route=>{started=true;await new Promise(resolve=>setTimeout(resolve,150));try{await route.fulfill({status:200,body:'not a ZIP'});}catch{/* canceled */}});
 const pending=rpc(page,'packageImport',{url:origin+'/slow-package.zip'});pending.catch(()=>{});while(!started)await new Promise(resolve=>setTimeout(resolve,5));await rpc(page,'cancel');await assert.rejects(()=>pending,/abort/i);assert.equal((await rpc(page,'list')).length,1);
 }finally{await context.close();}});

test('Android upgrade retires an old service-worker shell while preserving figures and video bytes',async()=>{const{page,context}=await setup();try{
 await page.goto(origin+'/assets/index.html');await page.waitForFunction(()=>window.nativeReady);const f=await create(page,'Bestehende Android-Figur');
 await page.evaluate(async()=>{await caches.open('salsa-trainer:/assets/:obsolete-shell');await navigator.serviceWorker.register('/assets/old-upgrade-sw.js',{scope:'/assets/'});await navigator.serviceWorker.ready;if(!navigator.serviceWorker.controller)await new Promise(resolve=>navigator.serviceWorker.addEventListener('controllerchange',resolve,{once:true}));});
 await page.goto(origin+'/assets/index.html');assert.match(await page.locator('body').innerText(),/OLD ANDROID SHELL/);
 await page.goto(origin+'/android-bootstrap.html');await page.waitForURL(origin+'/assets/index.html');await page.waitForFunction(()=>window.nativeReady);
 const all=await rpc(page,'list');assert.equal(all[0].id,f.id);assert.equal(all[0].status,'Am Üben');const media=await rpc(page,'media',{id:f.id,video:f.videoIds[0]});const bytes=await page.evaluate(t=>NativeMedia.data.get(t).flatMap(b=>Array.from(atob(b),c=>c.charCodeAt(0))),media.token);assert.deepEqual(Buffer.from(bytes),await readFile(new URL('./fixtures/video.mp4',import.meta.url)));
 assert.equal(await page.evaluate(async()=>(await navigator.serviceWorker.getRegistrations()).length),0);assert.ok(!(await page.evaluate(()=>caches.keys())).includes('salsa-trainer:/assets/:obsolete-shell'));
 }finally{await context.close();}});

test('Cloud UI receives shared videos in the original editor and Android back protects unsaved edits',async()=>{
 const {page,context}=await setup();try{
 await page.evaluate(()=>{delete window.NativeUI;window.AndroidShell={home(){window.closedByAndroid=true;}};});
 assert.equal(await page.evaluate(()=>AndroidAppUI.canReceive()),true);
 await page.evaluate(async url=>AndroidAppUI.receive([{url,name:'Drehung.mp4',type:'video/mp4'},{url,name:'Schritte.mp4',type:'video/mp4'}]),origin+'/fixture.mp4');
 assert.equal(await page.locator('#editor').evaluate(e=>e.open),true);
 assert.equal(await page.locator('#draftVideos .draft-card').count(),2);
 assert.equal(await page.locator('#figureTitle').inputValue(),'Drehung');
 assert.equal(await page.locator('#saveFigure').isDisabled(),true);
 await assert.rejects(()=>page.evaluate(()=>AndroidAppUI.receive([{url:'/fixture.mp4',name:'x.mp4',type:'video/mp4'}])),/aktuellen Vorgang/);
 await page.evaluate(()=>AndroidAppUI.back());
 await page.waitForFunction(()=>document.querySelector('#confirmDialog')?.open);
 assert.match(await page.locator('#confirmDialog').innerText(),/Änderungen verwerfen/);
 }finally{await context.close();}
});

test('Android back closes the focused backup dialog before its underlying settings dialog',async()=>{
 const {page,context}=await setup();try{
 await page.locator('#infoButton').click();
 await page.locator('#restoreBackup').click();
 assert.equal(await page.locator('#backupDialog').evaluate(e=>e.open),true);
 await page.evaluate(()=>AndroidAppUI.back());
 assert.equal(await page.locator('#backupDialog').evaluate(e=>e.open),false);
 assert.equal(await page.locator('#infoDialog').evaluate(e=>e.open),true);
 }finally{await context.close();}
});

test('Android header stays visible while scrolling and compact navigation meets the viewport bottom',async()=>{
 const {page,context}=await setup();try{
 await page.setViewportSize({width:390,height:844});
 await page.evaluate(()=>{const list=document.querySelector('#figureList');for(let i=0;i<25;i++){const card=document.createElement('article');card.className='figure-card';card.textContent='Figur '+i;card.style.minHeight='100px';list.append(card);}});
 const before=await page.locator('.overview > .heading').boundingBox();
 await page.evaluate(()=>window.scrollTo(0,800));
 await page.waitForTimeout(100);
 const header=await page.locator('.overview > .heading').boundingBox();
 const intro=await page.locator('.page-intro').boundingBox();
 const nav=await page.locator('body > .bottom-nav').boundingBox();
 const filters=await page.locator('.overview-filters').boundingBox();
 assert.ok(Math.abs(filters.y-intro.y-intro.height)<1,'search and filter row must stay below the intro');
 assert.equal(await page.locator('#search').isVisible(),true);
 assert.equal(header.y,0);assert.equal(header.height,before.height);
 assert.ok(Math.abs(intro.y-header.height)<1,'heading rows must not overlap');
 assert.equal(nav.y+nav.height,844);assert.ok(nav.height<=60,'navigation should be compact');
 await page.setViewportSize({width:844,height:390});
 await page.waitForTimeout(100);
 const landscape=await page.locator('body > .bottom-nav').boundingBox();
 assert.equal(landscape.y+landscape.height,390);
 }finally{await context.close();}
});

test('last figure menu opens above its trigger and keeps every action above the footer',async()=>{
 const {page,context}=await setup();try{
 await page.setViewportSize({width:390,height:844});
 await create(page,'Unterste Figur');
 await page.evaluate(()=>AndroidAppUI.show('overview'));
 await page.evaluate(()=>{const card=document.querySelector('.figure-card');card.style.marginTop='600px';window.scrollTo(0,document.body.scrollHeight);});
 await page.locator('.card-options').click();
 const menu=await page.locator('.card-menu').boundingBox();
 const trigger=await page.locator('.card-options').boundingBox();
 const footer=await page.locator('body > .bottom-nav').boundingBox();
 const header=await page.locator('.page-intro').boundingBox();
 assert.ok(menu.y+menu.height<=trigger.y,'last-card menu must open upward');
 assert.ok(menu.y>=header.y+header.height,'menu must clear the fixed header');
 assert.ok(menu.y+menu.height<footer.y,'menu must clear navigation');
 await page.evaluate(()=>AndroidAppUI.back());
 assert.equal(await page.locator('.card-menu').count(),0);
 await page.locator('.card-options').click();
 await page.getByRole('button',{name:'Bearbeiten',exact:true}).click();
 await page.waitForFunction(()=>document.querySelector('#editor').open);
 assert.equal(await page.locator('#editor').evaluate(e=>e.open),true);
 }finally{await context.close();}
});

test('folder player opens before media is read, loads only selected videos and ignores late selections',async()=>{
 const {page,context}=await setup();try{
 const draft=await rpc(page,'edit');await add(page,draft,'Erstes');const videos=await add(page,draft,'Zweites');
 const f=await rpc(page,'save',{token:draft.token,title:'Ordnertraining',description:'',status:'Neu',tags:[],videos});
 const folder=(await rpc(page,'folder',{action:'choose'})).activeFolder;
 const copy=await rpc(page,'transfer',{id:f.id,target:folder});
 await page.evaluate(()=>AndroidAppUI.show('overview'));
 await page.evaluate(()=>{const original=window.fetch;window.videoReads=[];window.pendingReads=[];window.holdReads=true;window.fetch=async(...args)=>{if(String(args[0]).includes('/mock/')&&decodeURIComponent(String(args[0])).includes('/media/')){window.videoReads.push(String(args[0]));if(window.holdReads)await new Promise(r=>window.pendingReads.push(r));}return original(...args);};});
 await page.locator(`[data-figure-id="${copy.id}"] .card-main`).click();
 await page.waitForFunction(()=>window.videoReads.length===1);
 assert.equal(await page.locator('#playerScreen').evaluate(e=>e.hidden),false);
 await page.locator('#playerVideoChoice').selectOption('1');
 await page.waitForFunction(()=>window.videoReads.length===2);
 await page.evaluate(()=>window.pendingReads[1]());
 await page.waitForFunction(()=>document.querySelector('#learningVideo').readyState>=2);
 const second=await page.locator('#learningVideo').getAttribute('src');
 await page.evaluate(()=>window.pendingReads[0]());
 await page.waitForTimeout(100);
 assert.equal(await page.locator('#learningVideo').getAttribute('src'),second);
 await page.locator('#playerVideoChoice').selectOption('0');
 await page.waitForFunction(()=>document.querySelector('#learningVideo').readyState>=2);
 assert.equal(await page.evaluate(()=>window.videoReads.length),2,'switching back must reuse the verified video');
 await page.evaluate(()=>AndroidAppUI.back());
 await page.waitForFunction(()=>document.querySelector('#playerScreen').hidden);
 await page.evaluate(async()=>{
   window.holdReads=false;
   const url=window.videoReads[0],path=decodeURIComponent(url.split('/mock/')[1]);
   const bytes=new Uint8Array(await(await fetch(url)).arrayBuffer());bytes[bytes.length-1]^=1;
   const call=a=>JSON.parse(AndroidFiles.call(JSON.stringify(a))).value;
   const token=call({op:'begin',root:'grant',path});
   call({op:'write',token,data:btoa(String.fromCharCode(...bytes))});call({op:'close',token});
 });
 await page.locator(`[data-figure-id="${copy.id}"] .card-main`).click();
 await page.waitForFunction(()=>document.querySelector('#playerError').textContent.includes('verändert'));
 assert.equal(await page.locator('#learningVideo').getAttribute('src'),null,'changed bytes must not be played after reopening');
 }finally{await context.close();}
});

test('Android folder writes preserve all bytes across larger bridge blocks',async()=>{
 const {page,context}=await setup();try{
 const result=await page.evaluate(async()=>{
  const original=AndroidFiles.call.bind(AndroidFiles),sizes=[];let asynchronous=false;
  AndroidFiles.closeAsync=(token,id)=>{asynchronous=true;setTimeout(()=>AndroidFSWriteResult(id,JSON.parse(original(JSON.stringify({op:'close',token})))),0);};
  AndroidFiles.call=raw=>{const a=JSON.parse(raw);if(a.op==='write')sizes.push(atob(a.data).length);return original(raw);};
  const root=await showDirectoryPicker(),file=await root.getFileHandle('large-test.mp4',{create:true});
  const expected=Uint8Array.from({length:900017},(_,i)=>i%251),writer=await file.createWritable();
  await writer.write(new Blob([expected]));await writer.close();
  const actual=new Uint8Array(await(await file.getFile()).arrayBuffer());
  return {sizes,asynchronous,length:actual.length,same:actual.every((b,i)=>b===expected[i])};
 });
 assert.equal(result.same,true);assert.equal(result.asynchronous,true);assert.equal(result.length,900017);assert.equal(result.sizes.length,5);assert.ok(result.sizes.every(n=>n<=196608));
 }finally{await context.close();}
});

test('Android shows the saving message and activity indicator while video storage is pending',async()=>{
 const {page,context}=await setup();try{
 await page.evaluate(()=>{delete window.NativeUI;window.AndroidShell={home(){}};});
 await page.evaluate(async url=>AndroidAppUI.receive([{url,name:'Speichertest.mp4',type:'video/mp4'}]),origin+'/fixture.mp4');
 await page.locator('#reviewAll').click();await page.waitForFunction(()=>!document.querySelector('#acceptVideo').disabled);await page.locator('#acceptVideo').click();
 await page.evaluate(()=>{const put=Cache.prototype.put;Cache.prototype.put=async function(request,...args){if(String(request).includes('__private_media__'))await new Promise(resolve=>window.releaseSaving=resolve);return put.call(this,request,...args);};});
 await page.locator('#saveFigure').click();await page.waitForFunction(()=>!!window.releaseSaving);
 assert.equal(await page.locator('#saveProgress').innerText(),'Figur inkl. Video(s) werden gespeichert...');assert.equal(await page.locator('#saveIndicator').isVisible(),true);assert.equal(await page.locator('#saveFigure').isDisabled(),true);
 await page.evaluate(()=>window.releaseSaving());await page.waitForFunction(()=>!document.querySelector('#editor').open);
 assert.equal(await page.locator('#saveIndicator').isVisible(),false);
 assert.equal(await page.getByRole('button',{name:'Speichertest lernen',exact:true}).count(),1);
 }finally{await context.close();}
});

// Shared contract is byte-for-byte identical to the PWA's contract.
const {PARITY_CASES,runParityCase}=await import('./parity-cases.js');
for(const scenario of PARITY_CASES)test('shared feature contract: '+scenario,async()=>{
 const {page,context}=await setup();try{
  const errors=[];page.on('console',m=>{if(m.type()==='error'&&m.text().includes('Content Security Policy'))errors.push(m.text());});
  await page.reload();await page.waitForFunction(()=>window.nativeReady);await page.evaluate(()=>document.fonts.ready);
  await runParityCase(page,{root:'/',fixture:'/fixture.mp4',native:true,scenario,consoleErrors:errors});
 }finally{await context.close();}
});


test('ZIP save adapter streams original bytes in bounded chunks and reports native errors',async()=>{
 const {page,context}=await setup();try{
 const result=await page.evaluate(async()=>{
  const bytes=Uint8Array.from({length:900017},(_,i)=>i%251),progress=[];
  const result=await stepFileDelivery.save(new File([bytes],'Native-Test.zip',{type:'application/zip'}),text=>progress.push(text));
  const decoded=AndroidDownloads.parts.flatMap(part=>Array.from(atob(part),c=>c.charCodeAt(0)));
  const same=decoded.length===bytes.length&&decoded.every((b,i)=>b===bytes[i]),bounded=AndroidDownloads.parts.every(part=>atob(part).length<=49152);
  let aborted=false,finished=false;AndroidDownloads.chunk=()=>false;AndroidDownloads.abort=()=>aborted=true;AndroidDownloads.finish=()=>{finished=true;return true;};
  let error;try{await stepFileDelivery.save(new File([bytes],'Retry.zip'));}catch(e){error=e.message;}
  return{result,same,bounded,progress:progress.at(-1),aborted,finished,error};
 });
 assert.equal(result.result,'native');assert.equal(result.same,true);assert.equal(result.bounded,true);assert.match(result.progress,/100 %/);assert.equal(result.aborted,true);assert.equal(result.finished,false);assert.match(result.error,/Speicher/);
 }finally{await context.close();}
});


test('native Android storage uses an information status without a browser permission button',async()=>{
 const {page,context}=await setup();try{
 await page.evaluate(()=>{window.persistCalls=0;Object.defineProperty(navigator.storage,'persist',{value:async()=>{window.persistCalls++;return false;},configurable:true});});
 await page.locator('#infoButton').click();await page.waitForFunction(()=>document.querySelector('#persistenceResult').textContent.includes('Keine zusätzliche Speicherfreigabe erforderlich'));
 assert.equal(await page.locator('#persistStorage').isVisible(),false);assert.match(await page.locator('#storageDetails').textContent(),/privaten Android-App-Speicher/);assert.equal(await page.evaluate(()=>persistCalls),0);
 }finally{await context.close();}
});

test('native delivery cancellation aborts partial files and a rejected start leaves the running export untouched',async()=>{
 const {page,context}=await setup();try{
 const result=await page.evaluate(async()=>{
  const controller=new AbortController();let aborted=0,finished=0,error='';
  AndroidDownloads.abort=()=>aborted++;AndroidDownloads.finish=()=>{finished++;return true;};
  try{await stepFileDelivery.save(new File([new Uint8Array(160000)],'cancel.zip'),()=>controller.abort(),controller.signal);}catch(e){error=e.name;}
  const parts=AndroidDownloads.parts.length;
  AndroidDownloads.start=()=>false;try{await stepFileDelivery.save(new File(['x'],'busy.zip'));}catch{}
  return {aborted,finished,error,parts};
 });
 assert.deepEqual(result,{aborted:1,finished:0,error:'AbortError',parts:1});
 }finally{await context.close();}
});

test('legacy native Figur-ZIP passes cancellation through the shared delivery adapter',async()=>{
 const {page,context}=await setup();try{
 const f=await create(page);
 const result=await page.evaluate(async f=>{
  let entered=false,aborted=false;
  stepFileDelivery.save=async(file,progress,signal)=>{
   entered=true;if(!signal)throw Error('Missing cancellation signal');
   await new Promise(resolve=>{signal.addEventListener('abort',()=>{aborted=true;resolve();},{once:true});
    NativeModel.request(9002,'cancel',{});
   });signal.throwIfAborted();
  };
  const reply=await new Promise(resolve=>{window.replies.set(9001,resolve);NativeModel.request(9001,'packageExport',{id:f.id,videos:f.videoIds});});
  return {entered,aborted,error:reply.error};
 },f);
 assert.equal(result.entered,true);assert.equal(result.aborted,true);assert.ok(result.error);
 }finally{await context.close();}
});

test('bundled media descriptors share the PWA lazy-loading and retry contract',async()=>{const{page,context}=await setup();try{
 const{mediaContract,expectedMediaContract}=await import('./media-contract.js');
 assert.deepEqual(await mediaContract(page,'/assets/'),expectedMediaContract);
 }finally{await context.close();}});

test('incoming preparation reserves the UI and cancellation suppresses a late read before retry', async()=>{
 const {page,context}=await setup();let release;const held=new Promise(resolve=>release=resolve);try{
 let started=false;
 await page.route(origin+'/held-video.mp4',async route=>{started=true;await held;try{await route.fulfill({status:200,contentType:'video/mp4',body:await readFile(new URL('./fixtures/video.mp4',import.meta.url))});}catch{/* aborted */}});
 await page.evaluate(url=>{
   window.firstIncoming=AndroidAppUI.receive([{url,name:'Verworfen.mp4',type:'video/mp4'}],'first').then(()=> 'completed',e=>e.name);
 },origin+'/held-video.mp4');
 while(!started)await new Promise(resolve=>setTimeout(resolve,5));
 assert.equal(await page.evaluate(()=>AndroidAppUI.canReceive()),false);
 assert.equal(await page.evaluate(()=>AndroidAppUI.beginReceive('second')),false);
 await assert.rejects(()=>page.evaluate(()=>AndroidAppUI.receive([{url:'/fixture.mp4',name:'Unerwartet.mp4',type:'video/mp4'}],'second')),/aktuellen Vorgang/);
 await page.evaluate(()=>AndroidAppUI.cancelReceive('first'));
 assert.equal(await page.evaluate(()=>window.firstIncoming),'AbortError');
 assert.equal(await page.locator('#editor').evaluate(e=>e.open),false);
 assert.equal(await page.evaluate(()=>AndroidAppUI.beginReceive('retry')),true);
 await page.evaluate(()=>AndroidAppUI.cancelReceive('first'));
 assert.equal(await page.evaluate(()=>AndroidAppUI.canReceive()),false,'old cancellation cannot clear a newer intake');
 release();
 await page.evaluate(url=>AndroidAppUI.receive([{url,name:'Erneut.mp4',type:'video/mp4'}],'retry'),origin+'/fixture.mp4');
 assert.equal(await page.locator('#figureTitle').inputValue(),'Erneut');
 assert.equal(await page.locator('#draftVideos .draft-card').count(),1);
 }finally{release?.();await context.close();}
});

test('incoming read cannot replace an editor opened while the provider was pending',async()=>{
 const{page,context}=await setup();let release;const held=new Promise(resolve=>release=resolve);try{
 let started=false;
 await page.route(origin+'/pending-video.mp4',async route=>{started=true;await held;await route.fulfill({status:200,contentType:'video/mp4',body:await readFile(new URL('./fixtures/video.mp4',import.meta.url))});});
 await page.evaluate(url=>{window.pendingIncoming=AndroidAppUI.receive([{url,name:'Spät.mp4',type:'video/mp4'}]).then(()=>'',e=>e.message);},origin+'/pending-video.mp4');
 while(!started)await new Promise(resolve=>setTimeout(resolve,5));
 await page.locator('#addFigure').evaluate(e=>e.click());
 await page.locator('#figureTitle').fill('Mein Entwurf');
 release();
 assert.match(await page.evaluate(()=>window.pendingIncoming),/aktuellen Vorgang/);
 assert.equal(await page.locator('#figureTitle').inputValue(),'Mein Entwurf');
 assert.equal(await page.locator('#draftVideos .draft-card').count(),0);
 }finally{release?.();await context.close();}
});

test('native cancellation wins atomically even before its queued JavaScript abort arrives',async()=>{
 const{page,context}=await setup();try{
 await page.evaluate(()=>{window.AndroidIntake={claim(){return false;}};});
 await assert.rejects(()=>page.evaluate(url=>AndroidAppUI.receive([{url,name:'Zu spät.mp4',type:'video/mp4'}],'cancelled-native'),origin+'/fixture.mp4'),/Abgebrochen/);
 assert.equal(await page.locator('#editor').evaluate(e=>e.open),false);
 assert.equal(await page.evaluate(()=>AndroidAppUI.canReceive()),true);
 }finally{await context.close();}
});
