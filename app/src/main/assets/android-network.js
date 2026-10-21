// Keep the existing API/auth/storage contracts, using native HTTPS outside browser CORS.
(() => {
  if(!globalThis.AndroidNetwork)return;
  const original=globalThis.fetch.bind(globalThis),pending=new Map();
  const base64=bytes=>{let s='';for(const b of bytes)s+=String.fromCharCode(b);return btoa(s);};
  globalThis.AndroidNetworkResult=(id,result)=>{const p=pending.get(id);if(!p){AndroidNetwork.cancel(id);return;}pending.delete(id);result.error?p.reject(new TypeError(result.error)):p.resolve(result);};
  globalThis.fetch=async(input,options={})=>{
    const url=new URL(typeof input==='string'?input:input.url,location.href);
    if(url.origin!=='https://tzaprobvauikssnpiucl.supabase.co')return original(input,options);
    const id=crypto.randomUUID(),signal=options.signal;let token='',abort;
    signal?.throwIfAborted();
    try {
      let body=options.body??'';
      if(body instanceof Blob){token=NativeMedia.begin();if(!token)throw new Error('Nicht genügend Speicher');for(let p=0;p<body.size;p+=49152){signal?.throwIfAborted();if(!NativeMedia.chunk(token,base64(new Uint8Array(await body.slice(p,p+49152).arrayBuffer()))))throw new Error('Upload konnte nicht vorbereitet werden');}if(!NativeMedia.finish(token))throw new Error('Upload konnte nicht vorbereitet werden');body='';}
      const result=await new Promise((resolve,reject)=>{
        abort=()=>{pending.delete(id);AndroidNetwork.cancel(id);reject(signal.reason??new DOMException('Abgebrochen','AbortError'));};
        pending.set(id,{resolve,reject});signal?.addEventListener('abort',abort,{once:true});
        if(signal?.aborted){abort();return;}
        const headers=Object.fromEntries(new Headers(options.headers||{}));
        AndroidNetwork.start(id,url.href,(options.method||'GET').toUpperCase(),JSON.stringify(headers),String(body),token);
      });
      const local=await original(result.url,{signal});if(!local.ok)throw new Error('Cloud-Antwort nicht lesbar');
      if([204,205,304].includes(result.status)){await local.arrayBuffer();return new Response(null,{status:result.status});}
      return new Response(local.body,{status:result.status,headers:{'Content-Type':result.type}});
    } finally {if(abort)signal?.removeEventListener('abort',abort);if(token)NativeMedia.abort(token);}
  };
})();
