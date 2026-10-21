// Native views use the exact PWA repositories and exchange formats, never a second library.
import * as storage from './storage.js';
import { filterFigures } from './logic.js';
const sessions=new Map();
const bytes64=bytes=>{let s='';for(const b of bytes)s+=String.fromCharCode(b);return btoa(s);};
const poster=async blob=>blob?bytes64(new Uint8Array(await blob.arrayBuffer())):null;
const clean=async f=>({...f,poster:await poster(f.poster),posterBytes:undefined});
const summary=records=>records.map(v=>({id:v.id,name:v.name,duration:v.duration,confirmed:!!v.confirmed,missing:!!v.missing,problem:v.problem??'',size:v.blob?.size??0}));
const figure=async key=>{const all=await storage.listFigures();const f=all.find(f=>f.id===key.id&&(f.storageFolder??null)===(key.storageFolder??null));if(!f)throw new Error('Figur nicht mehr vorhanden');if(key.revision&&key.revision!==f.revision)throw new Error('Diese Figur wurde inzwischen geändert. Bitte neu laden.');return f;};
async function stream(blob) {
  const token=NativeMedia.begin(); if(!token)throw new Error('Nicht genügend Speicher');
  try {for(let p=0;p<blob.size;p+=49152)if(!NativeMedia.chunk(token,bytes64(new Uint8Array(await blob.slice(p,p+49152).arrayBuffer()))))throw new Error('Videokopie fehlgeschlagen');
    if(!NativeMedia.finish(token))throw new Error('Videokopie fehlgeschlagen');return token;
  }catch(e){NativeMedia.abort(token);throw e;}
}
async function imported(a) {
  const r=await fetch(a.url);if(!r.ok)throw new Error('Video nicht lesbar');
  const data=await r.blob();if(!data.size||!(a.duration>0))throw new Error('Video nicht abspielbar');
  return {id:crypto.randomUUID(),name:a.name,blob:data.slice(0,data.size,a.type||data.type||'video/mp4'),duration:a.duration,width:a.width||0,height:a.height||0,poster:a.poster?new Blob([Uint8Array.from(atob(a.poster),c=>c.charCodeAt(0))],{type:'image/jpeg'}):null,confirmed:true,existing:false};
}
const operations={
  async list(a){return Promise.all(filterFigures(await storage.listFigures(),a).map(clean));},
  async edit(a){const f=a.id?await figure(a):{id:crypto.randomUUID(),title:'',description:'',status:'Neu',tags:[]};const records=a.id?(await storage.figureVideos(f,true)).map(v=>({...v,existing:true,confirmed:!v.missing})):[];const token=crypto.randomUUID();sessions.set(token,{figure:f,records});return {token,figure:await clean(f),videos:summary(records)};},
  async add(a){const session=sessions.get(a.token);if(!session)throw new Error('Entwurf geschlossen');if(Array.isArray(a.keep))session.records=a.keep.map(k=>{const v=session.records.find(v=>v.id===k.id);if(!v)throw new Error('Video fehlt');return {...v,name:k.name};});const v=await imported(a);if(a.replace){const i=session.records.findIndex(v=>v.id===a.replace);if(i<0)throw new Error('Video nicht mehr vorhanden');session.records.splice(i,1,v);}else session.records.push(v);return summary(session.records);},
  async save(a){const s=sessions.get(a.token);if(!s)throw new Error('Entwurf geschlossen');const records=a.videos.map(v=>{const old=s.records.find(r=>r.id===v.id);if(!old)throw new Error('Video fehlt');return {...old,name:v.name};});if(!['Neu','Am Üben','Sitzt'].includes(a.status))throw new Error('Ungültiger Lernstand');const saved=await storage.saveFigure({...s.figure,title:a.title,description:a.description,status:a.status,tags:a.tags},records,s.figure.revision??null);sessions.delete(a.token);return clean(saved);},
  async media(a){const records=a.token?sessions.get(a.token)?.records:await storage.figureVideos(await figure(a));const v=records?.find(v=>v.id===a.video);if(!v||v.missing)throw new Error('Video fehlt. Bitte ersetzen.');return {token:await stream(v.blob),name:v.name,duration:v.duration};},
  async videos(a){return summary(await storage.figureVideos(await figure(a)));},
  async delete(a){await storage.deleteFigure(await figure(a));return true;},
  async drop(a){sessions.delete(a.token);return true;},
  async legacy(a){const all=await storage.listFigures();for(const f of a.figures){if(all.some(v=>v.id===f.id))continue;const record=await imported(f);await storage.saveFigure({id:f.id,title:f.title,description:f.description,status:f.status,tags:f.tags},[record]);}return true;}
};
globalThis.NativeModel={async request(id,op,args){try{if(!operations[op])throw new Error('Unbekannter Vorgang');NativeUI.result(id,JSON.stringify({value:await operations[op](args)}));}catch(e){NativeUI.result(id,JSON.stringify({error:e.message??'Vorgang fehlgeschlagen'}));}}};


import {cloudCall, loginWithLink, logout, uploadMedia, finishLogin} from './cloud-api.js';
import {importFigure, exportFigure, packageFilename} from './package.js';
import {posterPayload} from './cloud-posters.js';
import {createBackupWorkflow} from './backup-workflow.js';
let uploadController=null;
const backup=createBackupWorkflow(storage,{onChange:s=>{globalThis.NativeUI?.progress(JSON.stringify({busy:s.busy,canCancel:s.canCancel,message:s.message,progress:s.progress}));}});
const snapshot=()=>{const s=backup.getState();return {...s,data:s.data?.parts instanceof Array?{...s.data,parts:s.data.parts.map(p=>({name:p.name,size:p.blob.size}))}:s.data};};
async function deliver(blob,name){if(!AndroidDownloads.start(name))throw new Error('Ein Export läuft bereits');try{for(let p=0;p<blob.size;p+=49152)if(!AndroidDownloads.chunk(bytes64(new Uint8Array(await blob.slice(p,p+49152).arrayBuffer()))))throw new Error('Export fehlgeschlagen');if(!AndroidDownloads.finish(blob.type||'application/octet-stream'))throw new Error('Export fehlgeschlagen');return true;}catch(e){AndroidDownloads.abort();throw e;}}
async function newImport(data){const token=crypto.randomUUID();const f={id:crypto.randomUUID(),title:data.title,description:data.description,status:'Neu',tags:data.tags,importFingerprint:data.fingerprint};const records=data.drafts.map(d=>({...d,id:crypto.randomUUID()}));sessions.set(token,{figure:f,records});return{token,figure:await clean(f),videos:summary(records),duplicate:(await storage.listFigures()).some(f=>f.importFingerprint===data.fingerprint)};}
Object.assign(operations,{
  async settings(){return {...await storage.storageSettings(),theme:window.stepAppearance.get(),estimate:await navigator.storage.estimate()};},
  async theme(a){return window.stepAppearance.set(a.theme);},
  async folder(a){if(a.action==='choose'){const choice=await storage.chooseFolder();await storage.connectFolder(choice);}else if(a.action==='access')await storage.allowFolderAccess(a.id);else await storage.setStorageFolder(a.id||null);return operations.settings();},
  async transfer(a){const original=await figure(a);const saved=await storage.transferFigure(original,a.target||null);return clean(saved);},
  async packageImport(a){const r=await fetch(a.url);if(!r.ok)throw new Error('Datei nicht lesbar');return newImport(await importFigure(await r.blob()));},
  async packageExport(a){const f=await figure(a);const selected=(await storage.figureVideos(f)).filter(v=>a.videos.includes(v.id));return deliver(await exportFigure(f,selected),packageFilename(f.title));},
  async confirm(a){const s=sessions.get(a.token),v=s?.records.find(v=>v.id===a.video);if(!v)throw new Error('Video fehlt');if(!(a.duration>0))throw new Error('Video nicht abspielbar');Object.assign(v,{duration:a.duration,width:a.width,height:a.height,confirmed:true,poster:a.poster?new Blob([Uint8Array.from(atob(a.poster),c=>c.charCodeAt(0))],{type:'image/jpeg'}):v.poster});return summary(s.records);},
  async cloudPoster(a){return cloudCall('poster_get',a).catch(()=>({}));},
  async cloud(a){return cloudCall(a.action,a.payload||{});},
  async cloudState(){await finishLogin();let state=await cloudCall('state');if(state.me){const r=await cloudCall(state.me.is_admin?'admin_cleanup':'cleanup').catch(()=>null);if(r?.cleaned)state=await cloudCall('state');}return state;},
  async login(a){await loginWithLink(a.link);return true;},
  async logout(){await logout();return true;},
  async cloudDownload(a){const rev=a.revision,selected=rev.videos.filter(v=>a.videos.includes(v.id));if(!selected.length)throw new Error('Bitte Videos auswählen');if(selected.reduce((s,v)=>s+v.size,0)>250*1024*1024)throw new Error('Maximal 250 MB pro Übernahme');const drafts=[];for(const v of selected){const r=await cloudCall('media_url',{revision:rev.id,media:v.id});const response=await fetch(r.url,{signal:AbortSignal.timeout(120000)});if(!response.ok)throw new Error('Download fehlgeschlagen');const blob=await response.blob();if(blob.size!==v.size)throw new Error('Unvollständiger Download');drafts.push({name:v.name,blob:blob.slice(0,blob.size,v.type),confirmed:false,existing:false,duration:0,poster:null});}return newImport({title:rev.title,description:rev.description,tags:rev.tags,fingerprint:`cloud:${rev.id}:${selected.map(v=>v.id).join(',')}`,drafts});},
  async cloudUpload(a){const f=await figure(a);const records=(await storage.figureVideos(f)).filter(v=>a.videos.includes(v.id));if(!records.length)throw new Error('Bitte Videos auswählen');if(records.some(v=>v.blob.size>50000000))throw new Error('Maximal 50 MB pro Video');let job=null;uploadController=new AbortController();try{const r=await cloudCall('reserve',{figure:a.cloudFigure||null,videos:records.map(v=>({size:v.blob.size,type:v.blob.type}))});job=r.job;for(let i=0;i<records.length;i++){uploadController.signal.throwIfAborted();NativeUI.progress(JSON.stringify({busy:true,canCancel:true,message:`Video ${i+1} von ${records.length} hochladen …`}));await uploadMedia(r.media[i].path,records[i].blob,uploadController.signal);await cloudCall('finalize',{id:r.media[i].id});}uploadController.signal.throwIfAborted();const poster=await posterPayload(f.poster).catch(()=>null);const saved=await cloudCall('commit',{job,...(poster?{poster}:{}),public_allowed:a.allowed,title:f.title,description:f.description,tags:f.tags,videos:r.media.map((m,i)=>({id:m.id,name:records[i].name}))});job=null;if(a.library)await cloudCall('attach',{library:a.library,revision:saved.revision});return saved;}catch(e){if(job)await cloudCall('abort',{job}).catch(()=>{});throw e;}finally{uploadController=null;NativeUI.progress(JSON.stringify({busy:false,canCancel:false,message:''}));}},
  async cancel(){uploadController?.abort();backup.cancel();return true;},
  async backup(a){if(a.action==='files'){const files=[];for(const item of a.files){const response=await fetch(item.url);if(!response.ok)throw new Error('Sicherungsteil nicht lesbar');files.push(new File([await response.blob()],item.name));}await backup.readFiles(files);}else if(a.action==='download'){const part=backup.getState().data?.parts?.[a.index];if(!part)throw new Error('Sicherungsteil fehlt');await deliver(part.blob,part.name);}else if(a.action==='restore')await backup.restore(async()=>true);else{const allowed=['startCreate','create','startRestore','checkRestore','close','setChoice','setTarget','grantAccess'];if(!allowed.includes(a.action))throw new Error('Unbekannter Sicherungsvorgang');await backup[a.action](...(a.args||[]));}return snapshot();}
});
