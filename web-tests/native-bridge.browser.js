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
async function setup(contextOptions={}){const context=await browser.newContext(contextOptions);const page=await context.newPage();await page.addInitScript(()=>{
 window.nativeReady=false;window.replies=new Map();window.NativeUI={ready(){window.nativeReady=true;},result(id,json){const reply=window.replies.get(id);if(reply){window.replies.delete(id);reply(JSON.parse(json));}},progress(){},home(){}};
 window.NativeMedia={data:new Map(),begin(){const t=crypto.randomUUID();this.data.set(t,[]);return t;},chunk(t,b){this.data.get(t).push(b);return true;},finish(){return true;},abort(t){this.data.delete(t);}};
 window.AndroidPlatform={rotate(){},keepAwake(){},fullscreen(){}};
 window.AndroidDownloads={parts:[],start(name){this.name=name;this.parts=[];return true;},chunk(b){this.parts.push(b);return true;},finish(type){this.type=type;return true;},abort(){}};
 const fs=JSON.parse(localStorage.getItem('mock-native-fs')||'{"files":{},"dirs":[""]}');
 const save=()=>localStorage.setItem('mock-native-fs',JSON.stringify(fs));
 const decode=s=>Uint8Array.from(atob(s),c=>c.charCodeAt(0));const encode=b=>{let s='';for(const c of b)s+=String.fromCharCode(c);return btoa(s);};
 // Mutate the live fixture too: pending file calls must not overwrite an external timestamp change.
 window.touchMockMedia=()=>{for(const [path,file] of Object.entries(fs.files))if(path.includes('/media/'))file.time+=10000;save();};
 const writers=new Map();
 window.fileCalls=[];window.pendingLibraryReads=[];window.holdLibraryReads=localStorage.getItem('hold-startup-library')==='yes';
 window.AndroidFiles={pick(){setTimeout(()=>window.AndroidFSResult({root:'grant',path:'',name:'Android documents',kind:'directory'}));},call(raw){const a=JSON.parse(raw);window.fileCalls.push({op:a.op,path:a.path});let value;try{switch(a.op){case'permission':value=localStorage.getItem('deny-folder-access')==='yes'?'denied':'granted';break;case'child':{const path=[a.path,a.name].filter(Boolean).join('/');if(a.directory){if(!fs.dirs.includes(path)){if(!a.create)throw Error('NotFoundError');fs.dirs.push(path);}}else if(!(path in fs.files)){if(!a.create)throw Error('NotFoundError');fs.files[path]={data:'',time:1};}save();value={root:a.root,path,name:a.name,kind:a.directory?'directory':'file'};break;}case'list':value=[...fs.dirs,...Object.keys(fs.files)].filter(p=>p!==a.path&&p.startsWith(a.path?a.path+'/':'')&&!p.slice(a.path?a.path.length+1:0).includes('/')).map(p=>p.split('/').at(-1));break;case'stat':{const f=fs.files[a.path];if(!f)throw Error('NotFoundError');value={size:decode(f.data).length,lastModified:f.time,name:a.path.split('/').at(-1),url:'https://appassets.androidplatform.net/mock/'+encodeURIComponent(a.path)};break;}case'begin':value=crypto.randomUUID();writers.set(value,{path:a.path,parts:[]});break;case'write':writers.get(a.token).parts.push(decode(a.data));value=true;break;case'close':{const w=writers.get(a.token);const size=w.parts.reduce((s,p)=>s+p.length,0);const out=new Uint8Array(size);let p=0;w.parts.forEach(b=>{out.set(b,p);p+=b.length;});fs.files[w.path]={data:encode(out),time:Date.now()};save();writers.delete(a.token);value=true;break;}case'abort':writers.delete(a.token);value=true;break;case'remove':{const path=[a.path,a.name].filter(Boolean).join('/');if(!fs.files[path])throw Error('NotFoundError');delete fs.files[path];save();value=true;break;}default:throw Error('Unknown operation');}return JSON.stringify({value});}catch(e){return JSON.stringify({error:e.message,name:e.message==='NotFoundError'?'NotFoundError':'InvalidStateError'});}}};
 const cacheKeys=caches.keys.bind(caches);window.pendingShellCleanup=[];
 caches.keys=async()=>{if(localStorage.getItem('hold-shell-cleanup')==='yes')await new Promise(resolve=>window.pendingShellCleanup.push(resolve));return cacheKeys();};
 const fetchOriginal=window.fetch.bind(window);window.fetch=async(input,options)=>{const u=String(input);if(u.startsWith('https://appassets.androidplatform.net/mock/')){if(decodeURIComponent(u).endsWith('/library.json')&&window.holdLibraryReads)await new Promise(resolve=>window.pendingLibraryReads.push(resolve));const f=fs.files[decodeURIComponent(u.split('/mock/')[1])];return new Response(f?decode(f.data):null,{status:f?200:404,headers:{'Content-Type':'application/octet-stream'}});}return fetchOriginal(input,options);};
 });await page.goto(origin+'/index.html');await page.waitForFunction(()=>window.nativeReady);return{context,page};}
async function rpc(page,op,args={}){const reply=await page.evaluate(({op,args})=>new Promise(resolve=>{const id=Math.floor(Math.random()*1e9);window.replies.set(id,resolve);NativeModel.request(id,op,args);}),{op,args});if(reply.error)throw Error(reply.error);return reply.value;}
async function loginAndConfirm(page,link){
 const pending=rpc(page,'login',{link});pending.catch(()=>{});
 const prompt=page.locator('#cloudLoginConfirmation');await prompt.waitFor();
 assert.match(await prompt.innerText(),/writer@example.test/);
 assert.equal(await page.evaluate(()=>localStorage.getItem('salsa-cloud-session:/:v1')),null);
 await prompt.getByRole('button',{name:'Anmelden',exact:true}).click();await pending;
}
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
  if(url.pathname==='/auth/v1/verify')result={access_token:'test-token',refresh_token:'test-refresh',expires_in:3600,user:{id:'writer',email:'writer@example.test'}};
  else if(url.pathname==='/functions/v1/cloud'){
   const {action,payload}=request.postDataJSON();actions.push({action,payload});
   if(action==='state')result={me:{id:'native-owner',is_admin:false},libraries:[],own_figures:[]};
   if(action==='reserve')result={job:'job-1',media:payload.videos.map((_,i)=>({id:`media-${i}`,path:`user/video-${i}`}))};
   if(action==='commit')result={revision:'revision-1'};
  }else if(url.pathname.startsWith('/storage/v1/object/salsa-cloud/')){
   started=true;uploaded.push(request.postDataBuffer());if(stall)await new Promise(resolve=>setTimeout(resolve,150));
  }
  try{await route.fulfill({status:200,headers,contentType:'application/json',body:JSON.stringify(result)});}catch{/* canceled request */}
 });
 await loginAndConfirm(page,'https://rodriges17.github.io/SalsaAppCloud/?token_hash=unit-test-token-hash-1234567890123456&type=magiclink');
 await rpc(page,'cloudState');actions.length=0;
 const f=await create(page);await rpc(page,'cloudUpload',{id:f.id,videos:f.videoIds,library:'library-1',allowed:true});
 assert.deepEqual(actions.map(a=>a.action),['reserve','finalize','commit','attach']);assert.equal(actions[2].payload.public_allowed,true);assert.deepEqual(uploaded[0],await readFile(new URL('./fixtures/video.mp4',import.meta.url)));
 const linked=await page.evaluate(async f=>{
  const {createCloudLocalIndex}=await import('./cloud-local.js');return [...createCloudLocalIndex(localStorage,`salsa-cloud-local:${new URL('./',location.href).pathname}:v1`).selections('native-owner','revision-1',[f])[0]];
 },f);assert.deepEqual(linked,['media-0']);
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
import {PARITY_CASES,runParityCase} from './parity-cases.js';
for(const scenario of PARITY_CASES)test('shared feature contract: '+scenario,async()=>{
 const {page,context}=await setup(scenario==='navigation-return-hover'?{hasTouch:true,isMobile:true}:{});try{
  const errors=[];page.on('console',m=>{if(m.type()==='error'&&m.text().includes('Content Security Policy'))errors.push(m.text());});
  page.on('pageerror',error=>console.error('Cloud browser error:',error.message));
  await page.reload();await page.waitForFunction(()=>window.nativeReady);await page.evaluate(()=>document.fonts.ready);
  await runParityCase(page,{root:'/',fixture:'/fixture.mp4',native:true,scenario,consoleErrors:errors});
 }catch(error){
  console.error('Cloud failure context:',await page.evaluate(()=>({cloudOpen:document.querySelector('#cloudDialog')?.open,error:document.querySelector('#cloudError')?.textContent,dialogs:[...document.querySelectorAll('dialog[open]')].map(d=>d.id),focused:document.activeElement?.tagName,body:document.querySelector('#cloudBody')?.textContent})));
  throw error;
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

test('Android folder writer queues overlapping writes and duplicate close waits for the same result',async()=>{
 const{page,context}=await setup();try{
 const result=await page.evaluate(async()=>{
  const root=await showDirectoryPicker(),handle=await root.getFileHandle('queued.bin',{create:true});
  const original=AndroidFiles.call.bind(AndroidFiles);let closes=0,aborts=0;
  AndroidFiles.call=raw=>{if(JSON.parse(raw).op==='abort')aborts++;return original(raw);};
  AndroidFiles.closeAsync=(token,id)=>{closes++;setTimeout(()=>AndroidFSWriteResult(id,JSON.parse(original(JSON.stringify({op:'close',token})))),30);};
  const writer=await handle.createWritable(),first=writer.write(new Blob(['erster-'])),second=writer.write(new Blob(['zweiter']));
  const close=writer.close(),duplicate=writer.close();let settled=false;duplicate.then(()=>settled=true);
  await writer.abort();await new Promise(resolve=>setTimeout(resolve,5));const premature=settled;
  await Promise.all([first,second,close,duplicate]);
  let afterClose;try{await writer.write('ungültig');}catch(e){afterClose=e.name;}
  return {same:close===duplicate,premature,closes,aborts,text:await(await handle.getFile()).text(),afterClose};
 });
 assert.deepEqual(result,{same:true,premature:false,closes:1,aborts:0,text:'erster-zweiter',afterClose:'InvalidStateError'});
 }finally{await context.close();}
});

test('Android folder abort during a pending Blob read sends no late chunk and preserves the target',async()=>{
 const{page,context}=await setup();try{
 const result=await page.evaluate(async()=>{
  const root=await showDirectoryPicker(),handle=await root.getFileHandle('abort.bin',{create:true});
  const originalWriter=await handle.createWritable();await originalWriter.write('vorher');await originalWriter.close();
  const original=AndroidFiles.call.bind(AndroidFiles);let writes=0,aborts=0,release;
  AndroidFiles.call=raw=>{const op=JSON.parse(raw).op;if(op==='write')writes++;if(op==='abort')aborts++;return original(raw);};
  class SlowBlob extends Blob { slice(){return {arrayBuffer:()=>new Promise(resolve=>release=()=>resolve(new Uint8Array([1,2,3]).buffer))};} }
  const writer=await handle.createWritable(),reading=writer.write(new SlowBlob(['später'])).then(()=>'',e=>e.name);
  while(!release)await new Promise(resolve=>setTimeout(resolve,0));
  await writer.abort();await writer.abort();release();
  return {error:await reading,writes,aborts,text:await(await handle.getFile()).text()};
 });
 assert.deepEqual(result,{error:'AbortError',writes:0,aborts:1,text:'vorher'});
 }finally{await context.close();}
});

test('Android folder close errors reach every waiter and release staging for an explicit retry',async()=>{
 const{page,context}=await setup();try{
 const result=await page.evaluate(async()=>{
  const root=await showDirectoryPicker(),handle=await root.getFileHandle('retry.bin',{create:true});
  const original=AndroidFiles.call.bind(AndroidFiles);let closes=0,aborts=0;
  AndroidFiles.call=raw=>{if(JSON.parse(raw).op==='abort')aborts++;return original(raw);};
  AndroidFiles.closeAsync=(token,id)=>{closes++;setTimeout(()=>AndroidFSWriteResult(id,{error:'Ordnerzugriff entzogen',name:'NotAllowedError'}),0);};
  const writer=await handle.createWritable();await writer.write('nicht gespeichert');
  const close=writer.close(),duplicate=writer.close();
  const errors=await Promise.all([close.catch(e=>e.name),duplicate.catch(e=>e.name)]);
  await writer.abort();
  const empty=await(await handle.getFile()).text();
  AndroidFiles.closeAsync=(token,id)=>setTimeout(()=>AndroidFSWriteResult(id,JSON.parse(original(JSON.stringify({op:'close',token})))),0);
  const retry=await handle.createWritable();await retry.write('erneut');await retry.close();
  return {errors,closes,aborts,empty,text:await(await handle.getFile()).text()};
 });
 assert.deepEqual(result,{errors:['NotAllowedError','NotAllowedError'],closes:1,aborts:1,empty:'',text:'erneut'});
 }finally{await context.close();}
});

test('cloud figure marks locally stored selection and disables duplicate download on Android',async()=>{
 const {page,context}=await setup();try{
 await page.evaluate(async()=>{
  localStorage.setItem(`salsa-cloud-session:${new URL('./',location.href).pathname}:v1`,JSON.stringify({access_token:'account-a',refresh_token:'refresh-a',expires_at:Date.now()+3600000}));
  const s=await import('./storage.js'),blob=await(await fetch('/fixture.mp4')).blob();
  await s.saveFigure({id:'local-copy',title:'Renamed local figure',status:'Sitzt',tags:[],importFingerprint:'cloud:local-revision:v2,v1'},[{id:'v',name:'Video',blob,duration:1,confirmed:true},{id:'w',name:'Second video',blob,duration:1,confirmed:true}]);
 });
 await page.route('**/functions/v1/cloud',route=>{
  const {action}=route.request().postDataJSON();
  const state={me:{id:'A',email:'a@example.test',used:0,quota:500000000,upload_enabled:true},libraries:[],own_figures:[{id:'f',public_allowed:false,revisions:[{id:'local-revision',title:'Cloud Original',description:'',tags:[],created_at:'2026-10-23T00:00:00Z',videos:[{id:'v1',name:'One',size:20,type:'video/mp4'},{id:'v2',name:'Two',size:20,type:'video/mp4'}]}]}]};
  return route.fulfill({contentType:'application/json',body:JSON.stringify(action==='state'?state:action==='poster_get'?{poster:null}:{ok:true})});
 });
 await page.locator('#cloudButton').click();await page.getByRole('heading',{name:'Cloud Original',exact:true}).waitFor();
 await page.locator('.cloud-own-figure summary').first().click();
 const button=page.getByRole('button',{name:'Lokal öffnen',exact:true});await button.waitFor();
 assert.equal(await button.isEnabled(),true);assert.equal(await page.locator('.cloud-local-status').first().textContent(),'Lokal verfügbar');
 assert.equal(await page.locator('#cloudDialog').getByRole('checkbox').count(),0);assert.equal(await page.locator('.cloud-library-chevron').count(),0);
 assert.equal(await page.getByRole('button',{name:'Video „One“ abspielen',exact:true}).isVisible(),true);assert.equal(await page.getByRole('button',{name:'Video „Two“ abspielen',exact:true}).isVisible(),true);
 await button.click();await page.locator('#editor').waitFor();
 assert.equal(await page.locator('#figureTitle').inputValue(),'Renamed local figure');
 }finally{await context.close();}
});


test('Android startup shows cached figures and refreshes the catalog and checks videos in the background',async()=>{
 const {page,context}=await setup();try{
  const app=await create(page,'App Figur');const folder=(await rpc(page,'folder',{action:'choose'})).activeFolder;
  const copy=await rpc(page,'transfer',{id:app.id,target:folder});
  await page.evaluate(()=>{
   const fs=JSON.parse(localStorage.getItem('mock-native-fs'));
   const media=Object.keys(fs.files).find(p=>p.includes('/media/'));delete fs.files[media];
   const catalog=Object.keys(fs.files).find(p=>p.endsWith('library.json'));
   const library=JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(fs.files[catalog].data),c=>c.charCodeAt(0))));
   library.figures[0].title='App Figur aktualisiert';
   fs.files[catalog].data=btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(library))));
   localStorage.setItem('mock-native-fs',JSON.stringify(fs));localStorage.setItem('hold-startup-library','yes');
  });
  await page.reload();await page.waitForFunction(()=>window.nativeReady&&window.pendingLibraryReads.length>0);
  await page.evaluate(()=>{window.dispatchEvent(new Event('focus'));document.dispatchEvent(new Event('visibilitychange'));window.dispatchEvent(new Event('focus'));});
  await page.waitForTimeout(100);
  assert.equal(await page.evaluate(()=>window.pendingLibraryReads.length),1,'Lifecycle events restarted the startup scan');
  assert.equal(await page.locator('.figure-card').count(),2);
  assert.doesNotMatch(await page.locator(`[data-figure-id="${copy.id}"]`).innerText(),/wird geprüft/);
  assert.equal(await page.evaluate(()=>fileCalls.filter(c=>c.op==='stat'&&c.path.includes('/media/')).length),0);
  await page.locator('#search').fill('App Figur');assert.equal(await page.locator('.figure-card').count(),2);
  await page.evaluate(()=>{window.holdLibraryReads=false;window.pendingLibraryReads.splice(0).forEach(resolve=>resolve());});
  await page.waitForFunction(id=>document.querySelector(`[data-figure-id="${id}"]`)?.textContent.includes('App Figur aktualisiert'),copy.id);
  await page.waitForFunction(id=>document.querySelector(`[data-figure-id="${id}"]`)?.textContent.includes('Videodatei fehlt'),copy.id);
  assert.equal(await page.locator('#search').inputValue(),'App Figur');
  assert.doesNotMatch(await page.locator(`[data-figure-id="${copy.id}"]`).innerText(),/wird geprüft/);
 }finally{await context.close();}
});

test('Android Cloud navigation remains usable and shares a pending device-folder scan',async()=>{
 const {page,context}=await setup();try{
  const app=await create(page);const folder=(await rpc(page,'folder',{action:'choose'})).activeFolder;
  const copy=await rpc(page,'transfer',{id:app.id,target:folder});
  await page.evaluate(async()=>{
   const storage=await import('./storage.js');
   await storage.saveFigure({id:crypto.randomUUID(),title:'Viele lokale Videos',description:'',tags:[],status:'Neu'},
    Array.from({length:12},(_,i)=>({id:crypto.randomUUID(),name:`Video ${i}`,blob:new Blob(['video bytes'],{type:'video/mp4'}),confirmed:true,duration:1})));
  });
  await page.route('**/cloud-config.js',r=>r.fulfill({contentType:'text/javascript',body:`export const CLOUD_URL='${origin}';export const CLOUD_KEY='test';export const CLOUD_FILE_LIMIT=50000000;`}));
  await page.route(origin+'/functions/v1/cloud',async route=>{
   const {action}=route.request().postDataJSON();
   const library={id:'course',title:'Schneller Kurs',visibility:'public'};
   const value=action==='state'?{me:{id:'owner',upload_enabled:true},own_figures:[],libraries:[library]}:action==='library'?{library,entries:[],members:[],manage:false}:{ok:true};
   await route.fulfill({contentType:'application/json',body:JSON.stringify(value)});
  });
  await page.evaluate(()=>localStorage.setItem('salsa-cloud-session:/:v1',JSON.stringify({access_token:'test',refresh_token:'refresh',expires_at:Date.now()+3600000})));
  await page.reload();await page.waitForFunction(()=>window.nativeReady);
  await page.waitForFunction(id=>{const card=document.querySelector(`[data-figure-id="${id}"]`);return card&&!card.textContent.includes('wird geprüft');},copy.id);
  await page.waitForTimeout(100); // Let the lightweight startup catalog refresh settle.
  await page.evaluate(()=>{window.holdLibraryReads=true;});
  await page.locator('#cloudButton').click();await page.locator('.cloud-figures-heading').getByRole('heading',{name:'Meine Figuren',exact:true}).waitFor();
  await page.getByRole('button',{name:'Bibliotheken',exact:true}).click();
  await page.locator('a.cloud-library-card').click();await page.getByRole('heading',{name:'Schneller Kurs',exact:true}).waitFor();
  await page.getByRole('button',{name:'Meine Cloud-Figuren',exact:true}).click();
  await page.locator('.cloud-figures-heading').getByRole('heading',{name:'Meine Figuren',exact:true}).waitFor();
  assert.equal(await page.evaluate(()=>window.pendingLibraryReads.length),1,'Tab and library navigation must share one pending native folder read');
  await page.evaluate(()=>{
   const call=AndroidFiles.call.bind(AndroidFiles);
   const total=Object.keys(JSON.parse(localStorage.getItem('mock-native-fs')).files).filter(path=>path.includes('/media/')).length;
   let probes=0;
   AndroidFiles.call=raw=>{
    const request=JSON.parse(raw);
    if(request.op==='stat'&&request.path.includes('/media/')){
     probes++;
     if(probes===1)setTimeout(()=>[...document.querySelectorAll('#cloudNavigation button')].find(b=>b.textContent==='Bibliotheken').click(),0);
     if(probes===total)window.navigationDuringScan=[...document.querySelectorAll('#cloudBody h3')].some(h=>h.textContent==='Bibliotheken');
    }
    return call(raw);
   };
   window.holdLibraryReads=false;window.pendingLibraryReads.splice(0).forEach(resolve=>resolve());
  });
  await page.waitForFunction(()=>window.navigationDuringScan!==undefined);
  assert.equal(await page.evaluate(()=>window.navigationDuringScan),true,'Queued navigation must run before the synchronous native media scan finishes');
  assert.equal(await page.getByRole('heading',{name:'Bibliotheken',exact:true}).isVisible(),true);
 }finally{await context.close();}
});

test('Android opens Cloud from the shell before a device-folder refresh finishes',async()=>{
 const {page,context}=await setup();try{
  const app=await create(page);const folder=(await rpc(page,'folder',{action:'choose'})).activeFolder;
  const copy=await rpc(page,'transfer',{id:app.id,target:folder});
  await page.route('**/functions/v1/cloud',route=>route.fulfill({contentType:'application/json',body:JSON.stringify({me:null,libraries:[],own_figures:[]})}));
  await page.reload();await page.waitForFunction(()=>window.nativeReady);
  await page.waitForFunction(id=>{const card=document.querySelector(`[data-figure-id="${id}"]`);return card&&!card.textContent.includes('wird geprüft');},copy.id);
  await page.evaluate(()=>{
   window.holdLibraryReads=true;
   window.cloudEntry=AndroidAppUI.show('cloud');window.cloudEntry.catch(()=>{});
  });
  await page.locator('#cloudDialog').waitFor({state:'visible',timeout:1500});
  await page.waitForFunction(()=>window.pendingLibraryReads.length>0);
  assert.equal(await page.locator('#cloudDialog').isVisible(),true);
  await page.evaluate(()=>{window.holdLibraryReads=false;window.pendingLibraryReads.splice(0).forEach(resolve=>resolve());});
  await page.evaluate(()=>window.cloudEntry);
 }finally{await context.close();}
});

test('Android Cloud thumbnails use saved posters without inspecting device videos',async()=>{
 const {page,context}=await setup();try{
  await rpc(page,'folder',{action:'choose'});
  await page.evaluate(async()=>{
   const storage=await import('./storage.js'),canvas=document.createElement('canvas');canvas.width=64;canvas.height=64;
   canvas.getContext('2d').fillRect(0,0,64,64);
   const poster=await new Promise(resolve=>canvas.toBlob(resolve,'image/jpeg'));
   await storage.saveFigure({id:'poster-copy',title:'Lokales Vorschaubild',description:'',tags:[],status:'Neu',importFingerprint:'cloud:poster-revision:remote-video'},
    [{id:'poster-video',name:'Video',blob:await(await fetch('/fixture.mp4')).blob(),poster,confirmed:true,duration:1}]);
  });
  await page.route('**/functions/v1/cloud',route=>route.fulfill({contentType:'application/json',body:JSON.stringify({poster:null})}));
  await page.evaluate(async()=>{
   window.fileCalls.length=0;
   const {createPosterView}=await import('./cloud-posters.js');
   const root=document.createElement('div');root.id='poster-probe';root.style.cssText='position:fixed;top:0;left:0;width:64px;height:64px;z-index:9999';
   const picture=document.createElement('div');root.append(picture);document.body.append(root);
   window.posterProbe=createPosterView(root,null);posterProbe.add(picture,{id:'poster-revision',videos:[{id:'remote-video'}]});
  });
  await page.locator('#poster-probe img.cloud-thumbnail').waitFor();
  assert.equal(await page.evaluate(()=>fileCalls.filter(c=>c.op==='stat'&&c.path.includes('/media/')).length),0,'Rendering a thumbnail must not verify local video files');
  await page.evaluate(()=>posterProbe.dispose());
 }finally{await context.close();}
});

test('cached Android folder summaries do not probe files or claim access after permission is revoked',async()=>{
 const {page,context}=await setup();try{
  const app=await create(page);const folder=(await rpc(page,'folder',{action:'choose'})).activeFolder;
  const copy=await rpc(page,'transfer',{id:app.id,target:folder});
  const result=await page.evaluate(async id=>{
   const s=await import('./storage.js');window.fileCalls.length=0;localStorage.setItem('deny-folder-access','yes');
   const cached=(await s.listFigures({cached:true})).find(f=>f.id===id);
   const probes=window.fileCalls.length;
   const verified=(await s.listFigures()).find(f=>f.id===id);
   return {checking:cached.storageChecking,probes,accessRequired:verified.folderAccessRequired};
  },copy.id);
  assert.deepEqual(result,{checking:true,probes:0,accessRequired:true});
 }finally{await context.close();}
});


test('Android opens saved figures before obsolete shell cache deletion finishes',async()=>{
 const {page,context}=await setup();try{
  await page.goto(origin+'/assets/index.html');await page.waitForFunction(()=>window.nativeReady);
  const figure=await create(page,'Schnell sichtbar');
  await page.evaluate(async()=>{await caches.open('salsa-trainer:/assets/:obsolete-shell');localStorage.setItem('hold-shell-cleanup','yes');});
  await page.goto(origin+'/android-bootstrap.html');await page.waitForURL(origin+'/assets/index.html');
  await page.waitForFunction(()=>window.nativeReady&&window.pendingShellCleanup.length>0);
  assert.equal(await page.locator(`[data-figure-id="${figure.id}"]`).count(),1);
  await page.locator('#search').fill('keine Treffer');assert.equal(await page.locator('.figure-card').count(),0);
  await page.evaluate(()=>{localStorage.removeItem('hold-shell-cleanup');window.pendingShellCleanup.splice(0).forEach(resolve=>resolve());});
  await page.waitForFunction(async()=>!(await caches.keys()).includes('salsa-trainer:/assets/:obsolete-shell'));
  const media=await rpc(page,'media',{id:figure.id,video:figure.videoIds[0]});
  const bytes=await page.evaluate(token=>NativeMedia.data.get(token).flatMap(b=>Array.from(atob(b),c=>c.charCodeAt(0))),media.token);
  assert.deepEqual(Buffer.from(bytes),await readFile(new URL('./fixtures/video.mp4',import.meta.url)));
 }finally{await context.close();}
});

test('Android cloud shows platform role and expands person details inline',async()=>{
 const {page,context}=await setup();try{
 await page.route('**/cloud-config.js',r=>r.fulfill({contentType:'text/javascript',body:`export const CLOUD_URL='${origin}';export const CLOUD_KEY='test';export const CLOUD_FILE_LIMIT=50000000;`}));
 const calls=[];
 await page.route(origin+'/functions/v1/cloud',async route=>{
 const {action,payload}=route.request().postDataJSON();calls.push({action,payload});
 const result=action==='state'?{me:{id:'admin',email:'admin@example.test',is_admin:true,upload_enabled:true,quota:500000000,used:0},settings:{used:0,total_limit:900000000},people:[{id:'reader',email:'reader@example.test',upload_enabled:false,quota:250000000,used:0},{id:'writer',email:'writer@example.test',upload_enabled:true,quota:250000000,used:0}],libraries:[{id:'private',owner_id:'admin',manage:true,contribute:true,title:'Privatkurs',visibility:'private',collaborative:true}],own_figures:[]}:action==='library'?{library:{id:'private',owner_id:'admin',title:'Privatkurs',visibility:'private',collaborative:true},manage:true,contribute:true,entries:[],members:[{id:'writer',email:'writer@example.test',role:'reader',upload_enabled:true}]}:action==='admin_access'?{memberships:[]}:action==='admin_link'?{link:'https://example.invalid/?type=magiclink&token_hash=demo',expires_at:null}:{ok:true};
 await route.fulfill({contentType:'application/json',body:JSON.stringify(result)});
 });
 await page.evaluate(()=>localStorage.setItem('salsa-cloud-session:/:v1',JSON.stringify({access_token:'test-jwt',refresh_token:'test-refresh',expires_at:Date.now()+3600000})));
 await page.reload();await page.waitForFunction(()=>window.nativeReady);await page.locator('#cloudButton').click();await page.locator('#cloudAccountOpen').waitFor();
 await page.locator('#cloudAccountOpen').click();assert.equal(await page.getByText('Plattform-Administrator',{exact:true}).isVisible(),true);await page.evaluate(()=>window.AndroidAppUI.back());assert.equal(await page.locator('#cloudAccountMenu').isVisible(),false);assert.equal(await page.locator('#cloudDialog').isVisible(),true);
 await page.locator('#cloudAccountOpen').click();await page.getByRole('button',{name:'Plattformverwaltung',exact:true}).click();const writer=page.locator('.cloud-person-summary').filter({hasText:'writer@example.test'}),reader=page.locator('.cloud-person-summary').filter({hasText:'reader@example.test'});
 await writer.click();assert.equal(await writer.getAttribute('aria-expanded'),'true');await reader.click();assert.equal(await writer.getAttribute('aria-expanded'),'false');assert.equal(await page.locator('.cloud-person-details').count(),1);
 await writer.click();await page.getByRole('tab',{name:'Berechtigungen',exact:true}).click();await page.getByLabel('Figuren hochladen und Bibliotheken erstellen',{exact:true}).uncheck();await page.getByRole('button',{name:'Änderungen speichern',exact:true}).click();await page.locator('#confirmCancel').click();assert.equal(calls.some(c=>c.action==='admin_user'),false);
 await page.getByRole('tab',{name:'Aktionen',exact:true}).click();await page.locator('#confirmOK').click();await page.getByRole('button',{name:'Neuen Anmeldelink erstellen',exact:true}).click();await page.getByLabel('Einmaliger persönlicher Anmeldelink',{exact:true}).waitFor();assert.match(await page.locator('.cloud-login-link').innerText(),/genaue Ablaufzeit ist derzeit nicht bestätigt/);
 await page.locator('#cloudBack').click();await page.locator('#cloudAccountMenu').waitFor();await page.evaluate(()=>window.AndroidAppUI.back());await page.getByRole('button',{name:'Bibliotheken',exact:true}).click();await page.locator('a.cloud-library-card').click();await page.getByRole('button',{name:'Bibliothek bearbeiten',exact:true}).click();await page.getByRole('tab',{name:'Einstellungen',exact:true}).click();assert.equal(await page.getByLabel('Vollständige E-Mail-Adresse',{exact:true}).count(),0);await page.locator('.cloud-member-summary').filter({hasText:'writer@example.test'}).click();assert.equal(await page.locator('.cloud-member-details').count(),1);await page.getByRole('button',{name:'Mitglied hinzufügen',exact:true}).click();await page.getByRole('button',{name:'reader@example.test',exact:true}).click();assert.equal(await page.getByLabel('Bibliotheksrolle',{exact:true}).last().locator('option').count(),1);await page.locator('.cloud-member-add').getByRole('button',{name:'Abbrechen',exact:true}).click();assert.equal(calls.some(c=>c.action==='member'),false);
 }finally{await context.close();}
});

test('Android cloud matching reuses folder hashes without transferring videos and invalidates changed files',async()=>{
 const {page,context}=await setup();try {
  const original=await create(page);const folder=(await rpc(page,'folder',{action:'choose'})).activeFolder;
  const copy=await rpc(page,'transfer',{id:original.id,target:folder});
  const scan=()=>page.evaluate(async({id,folder})=>{
   const {listFigures,figureMedia}=await import('./storage.js');
   const {localVideoContent}=await import('./cloud-local-content.js');
   let transfers=0;const previous=window.fetch;
   window.fetch=async(input,options)=>{if(decodeURIComponent(String(input)).includes('/media/'))transfers++;return previous(input,options);};
   try {const figures=(await listFigures()).filter(f=>f.id===id&&f.storageFolder===folder);const result=await localVideoContent(figures,figureMedia);return {transfers,matches:result.size};}
   finally {window.fetch=previous;}
  },{id:copy.id,folder});
  assert.deepEqual(await scan(),{transfers:1,matches:1});
  assert.deepEqual(await scan(),{transfers:0,matches:1});
  await page.reload();await page.waitForFunction(()=>window.nativeReady);
  assert.deepEqual(await scan(),{transfers:0,matches:1});
  await page.evaluate(()=>window.touchMockMedia());
  await page.reload();await page.waitForFunction(()=>window.nativeReady);
  assert.deepEqual(await scan(),{transfers:0,matches:0});
 } finally {await context.close();}
});

test('folder editor opens descriptors and saves text without loading video bytes or focusing the keyboard',async()=>{
 const {page,context}=await setup();try{
  const draft=await rpc(page,'edit');const videos=await add(page,draft,'Video');
  const f=await rpc(page,'save',{token:draft.token,title:'Ohne Tastatur',description:'Vorher',status:'Neu',tags:[],videos});
  const folder=(await rpc(page,'folder',{action:'choose'})).activeFolder;
  const copy=await rpc(page,'transfer',{id:f.id,target:folder});
  await page.evaluate(()=>AndroidAppUI.show('overview'));
  await page.evaluate(()=>{const original=window.fetch;window.editorReads=[];window.fetch=async(...args)=>{
   if(String(args[0]).includes('/mock/')&&decodeURIComponent(String(args[0])).includes('/media/')){window.editorReads.push(String(args[0]));throw Error('unexpected video read');}
   return original(...args);
  };});
  await page.locator(`[data-figure-id="${copy.id}"] .card-options`).click();
  await page.getByRole('button',{name:'Bearbeiten',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#editorLoading').hidden);
  assert.equal(await page.locator('#videoCount').textContent(),'Videos · 1');
  assert.equal(await page.locator('#pickVideos').isEnabled(),true);
  assert.equal(await page.locator('#saveFigure').isEnabled(),true);
  assert.equal(await page.evaluate(()=>document.activeElement.matches('input,textarea,select')),false);
  await page.locator('#figureDescription').fill('Nur Text geändert');
  await page.locator('#saveFigure').click();await page.locator('#editor').waitFor({state:'hidden'});
  const stored=await page.evaluate(async key=>(await import('./storage.js')).readFigureMetadata(key),copy);
  assert.equal(stored.description,'Nur Text geändert');assert.deepEqual(stored.videoIds,copy.videoIds);
  assert.equal(await page.evaluate(()=>window.editorReads.length),0);
 }finally{await context.close();}
});

test('storage transfer dialog persists with quoted title and direction until video verification finishes',async()=>{
 const {page,context}=await setup();try{
  const draft=await rpc(page,'edit');const videos=await add(page,draft,'Video');
  let figure=await rpc(page,'save',{token:draft.token,title:'Enchufla',description:'',status:'Neu',tags:[],videos});
  await rpc(page,'folder',{action:'choose'});
  for(const direction of ['App-Speicher → Geräteordner','Geräteordner → App-Speicher']){
   await page.evaluate(()=>{const original=window.fetch;window.releaseTransfer=null;
    window.fetch=async(...args)=>{
     if(decodeURIComponent(String(args[0])).includes('/media/')){window.fetch=original;await new Promise(r=>window.releaseTransfer=r);}
     return original(...args);
    };
   });
   await page.evaluate(key=>{window.pendingTransfer=AndroidAppUI.show('transfer',key);}, {id:figure.id,storageFolder:figure.storageFolder??null});
   await page.locator('#confirmOK').click();
   await page.waitForFunction(()=>typeof window.releaseTransfer==='function');
   assert.equal(await page.locator('#transferProgressDialog').isVisible(),true);
   assert.equal(await page.locator('#transferProgressTitle').textContent(),'Figur wird übertragen');
   assert.equal(await page.locator('#transferProgressName').textContent(),'„Enchufla“');
   assert.equal(await page.locator('#transferProgressDirection').textContent(),direction);
   await page.waitForTimeout(4700);
   await page.evaluate(()=>AndroidAppUI.back());
   assert.equal(await page.locator('#transferProgressDialog').isVisible(),true);
   await page.evaluate(()=>window.releaseTransfer());
   await page.waitForFunction(()=>document.querySelector('#confirmTitle').textContent==='Kopie erfolgreich geprüft');
   assert.equal(await page.locator('#transferProgressDialog').isVisible(),false);
   await page.locator('#confirmOK').click();await page.evaluate(()=>window.pendingTransfer);
   assert.equal(await page.locator('#transferProgressDialog').isVisible(),false);
   assert.equal(await page.locator('#toast').textContent(),'Übertragung abgeschlossen.');
   const figures=await rpc(page,'list');assert.equal(figures.length,1);figure=figures[0];
  }
 }finally{await context.close();}
});

test('active Android cloud upload retains saved figure and retries attachment without media transfer',async()=>{
 const {page,context}=await setup();try{
  let fail=true,commits=0,transfers=0,saved=null;const calls=[];
  await page.route('https://tzaprobvauikssnpiucl.supabase.co/**',async route=>{
   const req=route.request(),url=new URL(req.url()),headers={'Access-Control-Allow-Origin':'*','Access-Control-Allow-Headers':'authorization,apikey,content-type','Access-Control-Allow-Methods':'POST,OPTIONS'};
   if(req.method()==='OPTIONS')return route.fulfill({status:204,headers});let result={ok:true};
   if(url.pathname==='/auth/v1/verify')result={access_token:'test-token',refresh_token:'refresh',expires_in:3600,user:{id:'writer',email:'writer@example.test'}};
   else if(url.pathname==='/functions/v1/cloud'){
    const {action,payload}=req.postDataJSON();calls.push(action);
    if(action==='state')result={me:{id:'writer',email:'writer@example.test',is_admin:false,upload_enabled:true,used:0,quota:500000000},libraries:[{id:'library',title:'Privat',visibility:'private',contribute:true}],own_figures:saved?[saved]:[]};
    if(action==='duplicates')result={matches:[]};
    if(action==='reserve')result={job:'job',media:payload.videos.map((v,i)=>({id:'media-'+i,path:'media/'+i}))};
    if(action==='commit'){commits++;saved={id:'cloud',owner_id:'writer',public_allowed:false,revisions:[{id:'revision',title:payload.title,description:payload.description,tags:payload.tags,videos:payload.videos}]};result={figure:'cloud',revision:'revision'};}
    if(action==='attach'&&fail)return route.fulfill({status:400,headers,contentType:'application/json',body:JSON.stringify({error:'Test-Zuordnung fehlgeschlagen'})});
   }else if(url.pathname.startsWith('/storage/'))transfers++;
   return route.fulfill({status:200,headers,contentType:'application/json',body:JSON.stringify(result)});
  });
  await loginAndConfirm(page,'https://rodriges17.github.io/SalsaAppCloud/?token_hash=test-token&type=magiclink');
  const figure=await create(page,'Aktiver Upload');
  await page.evaluate(key=>AndroidAppUI.show('cloud',key),{id:figure.id});
  await page.getByRole('combobox',{name:'Bibliothek',exact:true}).selectOption('library');
  await page.getByRole('button',{name:'Ausgewählte Figur hochladen',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#cloudUploadRecovery')?.textContent.includes('Bibliothekszuordnung fehlgeschlagen'));
  const sent=transfers;fail=false;await page.getByRole('button',{name:'Zuordnung erneut versuchen',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#cloudUploadRecovery').hidden);
  assert.equal(commits,1);assert.equal(transfers,sent);assert.equal(calls.filter(a=>a==='reserve').length,1);
 }finally{await context.close();}
});

test('active editor retains committed figure when overview loading fails and retry never saves twice',async()=>{
 const {page,context}=await setup();try{
  const f=await create(page,'Sicher gespeichert');await page.evaluate(()=>AndroidAppUI.show('overview'));
  await page.locator(`[data-figure-id="${f.id}"] .card-options`).click();await page.getByRole('button',{name:'Bearbeiten',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#editorLoading').hidden);
  await page.locator('#figureDescription').fill('Bleibt gespeichert');
  await page.evaluate(()=>{
   window.failEditorOverview=true;window.editorFigureWrites=0;
   const getAll=IDBObjectStore.prototype.getAll,put=IDBObjectStore.prototype.put;
   IDBObjectStore.prototype.getAll=function(...args){if(this.name==='figures'&&window.failEditorOverview)throw Error('Test: Übersicht offline');return getAll.apply(this,args);};
   IDBObjectStore.prototype.put=function(...args){if(this.name==='figures')window.editorFigureWrites++;return put.apply(this,args);};
  });
  await page.locator('#saveFigure').click();await page.locator('#retryEditorRefresh').waitFor();
  assert.match(await page.locator('#editorError').textContent(),/Figur ist gespeichert/);
  assert.equal(await page.locator('#saveFigure').isVisible(),false);
  assert.equal(await page.locator('#figureTitle').isDisabled(),true);
  await page.locator('#retryEditorRefresh').click();await page.waitForFunction(()=>!document.querySelector('#retryEditorRefresh').disabled);
  assert.equal(await page.evaluate(()=>window.editorFigureWrites),1);
  await page.evaluate(()=>window.failEditorOverview=false);
  await page.locator('#retryEditorRefresh').click();await page.waitForFunction(()=>!document.querySelector('#editor').open);
  const all=await rpc(page,'list');assert.equal(all.length,1);assert.equal(all[0].description,'Bleibt gespeichert');
  assert.equal(await page.evaluate(()=>window.editorFigureWrites),1);
 }finally{await context.close();}
});

test('active editor conflict keeps draft until confirmed reload and then uses the new revision',async()=>{
 const {page,context}=await setup();try{
  const f=await create(page,'Ursprünglich');await page.evaluate(()=>AndroidAppUI.show('overview'));
  await page.locator(`[data-figure-id="${f.id}"] .card-options`).click();await page.getByRole('button',{name:'Bearbeiten',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#editorLoading').hidden);
  await page.locator('#figureTitle').fill('Mein Entwurf');
  const elsewhere=await rpc(page,'edit',{id:f.id});
  await rpc(page,'save',{token:elsewhere.token,title:'Anderer Stand',description:'Extern',status:'Sitzt',tags:['Extern'],videos:elsewhere.videos});
  await page.locator('#saveFigure').click();await page.locator('#reloadEditor').waitFor();
  assert.equal(await page.locator('#figureTitle').inputValue(),'Mein Entwurf');assert.equal(await page.locator('#saveFigure').isDisabled(),true);
  await page.locator('#reloadEditor').click();await page.getByRole('button',{name:'Weiter bearbeiten',exact:true}).click();
  assert.equal(await page.locator('#figureTitle').inputValue(),'Mein Entwurf');
  await page.locator('#reloadEditor').click();await page.getByRole('button',{name:'Verwerfen und neu laden',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#figureTitle').value==='Anderer Stand');
  assert.equal(await page.locator('#figureDescription').inputValue(),'Extern');
  await page.locator('#figureDescription').fill('Nachgeladen und bearbeitet');await page.locator('#saveFigure').click();
  await page.waitForFunction(()=>!document.querySelector('#editor').open);
  assert.equal((await rpc(page,'list'))[0].description,'Nachgeladen und bearbeitet');
 }finally{await context.close();}
});

test('closing a checking editor prevents its delayed media from replacing a reopened draft',async()=>{
 const {page,context}=await setup();try{
  const f=await create(page,'Alte Prüfung');const folder=(await rpc(page,'folder',{action:'choose'})).activeFolder;
  const copy=await rpc(page,'transfer',{id:f.id,target:folder});await page.evaluate(()=>AndroidAppUI.show('overview'));
  await page.evaluate(()=>{const original=window.fetch;window.oldEditorReads=[];window.oldReadReturned=false;window.fetch=async(...args)=>{
   if(String(args[0]).includes('/mock/')&&decodeURIComponent(String(args[0])).endsWith('library.json')){
    await new Promise(resolve=>window.oldEditorReads.push(resolve));const response=await original(...args);window.oldReadReturned=true;return response;
   }return original(...args);
  };});
  await page.locator(`[data-figure-id="${copy.id}"] .card-options`).click();await page.getByRole('button',{name:'Bearbeiten',exact:true}).click();
  await page.waitForFunction(()=>window.oldEditorReads.length===1);await page.locator('#editorBack').click();
  await page.waitForFunction(()=>!document.querySelector('#editor').open);
  await page.locator(`[data-figure-id="${f.id}"] .card-options`).click();await page.getByRole('button',{name:'Bearbeiten',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#editorLoading').hidden);
  await page.locator('#figureTitle').fill('Neuer Entwurf');await page.evaluate(()=>window.oldEditorReads[0]());
  await page.waitForFunction(()=>window.oldReadReturned);
  assert.equal(await page.locator('#figureTitle').inputValue(),'Neuer Entwurf');
  assert.equal(await page.locator('#editorError').isVisible(),false);assert.equal(await page.locator('#saveFigure').isEnabled(),true);
 }finally{await context.close();}
});

test('file picker and sequential review save the reordered renamed local videos',async()=>{
 const {page,context}=await setup();try{
  await page.locator('#openAddFigure').click();await page.locator('#addFigure').click();
  const choosing=page.waitForEvent('filechooser');await page.locator('#pickVideos').click();
  const chooser=await choosing,bytes=await readFile(new URL('./fixtures/video.mp4',import.meta.url));
  await chooser.setFiles([{name:'Erstes.mp4',mimeType:'video/mp4',buffer:bytes},{name:'Zweites.mp4',mimeType:'video/mp4',buffer:bytes}]);
  assert.equal(await page.locator('#draftVideos .draft-card').count(),2);assert.equal(await page.locator('#saveFigure').isDisabled(),true);
  await page.locator('#reviewAll').click();
  for(const title of ['Erstes','Zweites']){
   await page.waitForFunction(name=>document.querySelector('#previewTitle').textContent===name,title);
   await page.waitForFunction(()=>!document.querySelector('#acceptVideo').disabled);await page.locator('#acceptVideo').click();
  }
  await page.waitForFunction(()=>!document.querySelector('#preview').open);
  await page.getByRole('button',{name:'Video 2 nach oben',exact:true}).click();
  await page.getByRole('textbox',{name:'Name von Video 1',exact:true}).fill('Vorne umbenannt');
  await page.locator('#draftVideos .draft-card').first().getByRole('button',{name:'Vorschau',exact:true}).click();
  assert.equal(await page.locator('#previewTitle').textContent(),'Vorne umbenannt');await page.locator('#previewBack').click();
  await page.waitForFunction(()=>!document.querySelector('#preview').open);
  await page.locator('#draftVideos .draft-card').last().getByRole('button',{name:'Entfernen',exact:true}).click();
  await page.locator('#saveFigure').click();await page.waitForFunction(()=>!document.querySelector('#editor').open);
  const all=await rpc(page,'list');assert.equal(all.length,1);assert.equal(all[0].videoIds.length,1);
  const editing=await rpc(page,'edit',{id:all[0].id});assert.equal(editing.videos[0].name,'Vorne umbenannt');
 }finally{await context.close();}
});

test('reopening from an old overview shows the current stored figure instead of stale summary metadata',async()=>{
 const {page,context}=await setup();try{
  const f=await create(page,'Alter Überblick');await page.evaluate(()=>AndroidAppUI.show('overview'));
  const fresh=await rpc(page,'edit',{id:f.id});
  await rpc(page,'save',{token:fresh.token,title:'Bereits gespeichert',description:'Aktueller Inhalt',status:'Sitzt',tags:['Aktuell'],videos:fresh.videos});
  await page.locator(`[data-figure-id="${f.id}"] .card-options`).click();await page.getByRole('button',{name:'Bearbeiten',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#editorLoading').hidden);
  assert.equal(await page.locator('#figureTitle').inputValue(),'Bereits gespeichert');
  assert.equal(await page.locator('#figureDescription').inputValue(),'Aktueller Inhalt');
  await page.locator('#figureTags').fill('Nur Suchtext');
  await page.locator('#editorBack').click();await page.waitForFunction(()=>!document.querySelector('#editor').open);
  assert.equal(await page.locator('#confirmDialog').isVisible(),false);
  await page.locator(`[data-figure-id="${f.id}"] .card-options`).click();await page.getByRole('button',{name:'Bearbeiten',exact:true}).click();
  await page.waitForFunction(()=>document.querySelector('#editorLoading').hidden);
  assert.equal(await page.locator('#figureTags').inputValue(),'');
 }finally{await context.close();}
});

import {runFigureCardsCase} from './figure-cards-case.js';
test('shared cloud card geometry preserves contextual information in both themes',async()=>{
 const {page,context}=await setup();try{await runFigureCardsCase(page,{root:'/',fixture:'/fixture.mp4'});}finally{await context.close();}
});
import {runCloudCardLifetimeCase} from './figure-cards-case.js';
test('shared cloud preview lifetime handles late responses in own and library cards',async()=>{
 const {page,context}=await setup();try{await runCloudCardLifetimeCase(page,{root:'/',fixture:'/fixture.mp4'});}finally{await context.close();}
});
