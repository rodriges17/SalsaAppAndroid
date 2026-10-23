// File System Access adapter over Android's Storage Access Framework. Only granted trees are addressable.
(() => {
  if (!globalThis.AndroidFiles) return;
  const call = args => { const r = JSON.parse(AndroidFiles.call(JSON.stringify(args))); if(r.error) throw new DOMException(r.error,r.name); return r.value; };
  const encode = bytes => { const parts=[]; for(let i=0;i<bytes.length;i+=8192)parts.push(String.fromCharCode(...bytes.subarray(i,i+8192))); return btoa(parts.join('')); };
  const finishing = new Map();
  globalThis.AndroidFSWriteResult = (id, result) => {
    const pending=finishing.get(id);if(!pending)return;finishing.delete(id);
    result.error ? pending.reject(new DOMException(result.error,result.name)) : pending.resolve(result.value);
  };
  const finishWrite = token => {
    if(typeof AndroidFiles.closeAsync!=='function')return Promise.resolve(call({op:'close',token}));
    return new Promise((resolve,reject)=>{
      const id=crypto.randomUUID();finishing.set(id,{resolve,reject});
      try{AndroidFiles.closeAsync(token,id);}catch(error){finishing.delete(id);reject(error);}
    });
  };
  class Handle {
    constructor(value) { Object.assign(this,value); }
    async queryPermission() { return call({op:'permission',root:this.root}); }
    async requestPermission() { return this.queryPermission(); }
    async isSameEntry(other) { return this.root===other.root&&this.path===other.path; }
    async getDirectoryHandle(name, options={}) { return new Handle(call({...this,op:'child',name,directory:true,create:!!options.create})); }
    async getFileHandle(name, options={}) { return new Handle(call({...this,op:'child',name,directory:false,create:!!options.create})); }
    async *keys() { for(const name of call({...this,op:'list'})) yield name; }
    async removeEntry(name) { call({...this,op:'remove',name}); }
    async getMetadata() { return call({...this,op:'stat'}); }
    async getFile() { const info=call({...this,op:'stat'}),r=await fetch(info.url); if(!r.ok)throw new DOMException('Datei nicht gefunden','NotFoundError'); const blob=await r.blob(); return new File([blob],info.name,{type:blob.type,lastModified:info.lastModified}); }
    async createWritable() {
      const token=call({...this,op:'begin'});
      let state='open', pending=Promise.resolve(), closing;
      const cancelled=()=>{if(state==='aborted')throw new DOMException('Schreibvorgang abgebrochen','AbortError');};
      return {
        write(value) {
          if(state!=='open')return Promise.reject(new DOMException('Schreibvorgang bereits geschlossen','InvalidStateError'));
          const blob=value instanceof Blob?value:new Blob([value]);
          const operation=pending.then(async()=>{
            cancelled();
            let yielded=performance.now();
            // Queue writes and bound each bridge call to 192 KiB.
            for(let p=0;p<blob.size;p+=196608){
              const bytes=new Uint8Array(await blob.slice(p,p+196608).arrayBuffer());
              cancelled();
              call({op:'write',token,data:encode(bytes)});
              if(performance.now()-yielded>=16){await new Promise(resolve=>setTimeout(resolve,0));yielded=performance.now();}
            }
            cancelled();
          });
          pending=operation;
          return operation;
        },
        close() {
          if(closing)return closing;
          if(state!=='open')return Promise.reject(new DOMException('Schreibvorgang bereits geschlossen','InvalidStateError'));
          // close wins over abort, including while earlier queued writes finish.
          state='closing';
          closing=(async()=>{
            try { await pending;await finishWrite(token);state='closed'; }
            catch(error){state='failed';try{call({op:'abort',token});}catch{}throw error;}
          })();
          return closing;
        },
        async abort() {
          if(state!=='open')return;
          state='aborted';call({op:'abort',token});
        }
      };
    }
  }
  globalThis.serializeAndroidHandle = h => h instanceof Handle ? {androidHandle:1,root:h.root,path:h.path,name:h.name,kind:h.kind}:h;
  globalThis.reviveAndroidHandle = h => h?.androidHandle ? new Handle(h):h;
  let pending;
  globalThis.AndroidFSResult=(value,error)=>{const p=pending;pending=null;if(!p)return;error?p.reject(new DOMException(error,'AbortError')):p.resolve(new Handle(value));};
  globalThis.showDirectoryPicker=()=>new Promise((resolve,reject)=>{if(pending)return reject(new Error('Ordnerauswahl läuft bereits'));pending={resolve,reject};AndroidFiles.pick();});
})();
