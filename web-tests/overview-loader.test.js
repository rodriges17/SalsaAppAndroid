import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createOverviewLoader } from '../app/src/main/assets/overview-loader.js';
const pending = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b; }); return {promise,resolve,reject}; };
function setup(list, options = {}) {
 const rendered=[], errors=[], deferred=[];
 const loader=createOverviewLoader({list,render:x=>rendered.push(x),failed:x=>errors.push(x),defer:x=>deferred.push(x), ...options});
 return {...loader,rendered,errors,deferred};
}
test('overview displays cached figures before a deferred folder check finishes', async()=>{
 const check=pending(), calls=[];
 const cached=[{id:'folder',storageChecking:true}], verified=[{id:'folder',storageProblem:'missing'}];
 const h=setup(options=>{calls.push(options);return options.cached?cached:check.promise;});
 await h.refresh({cached:true});assert.deepEqual(h.rendered,[cached]);assert.equal(calls.length,1);
 const done=h.deferred.shift()();assert.equal(calls[1].yieldToUI,true);assert.deepEqual(h.rendered,[cached]);
 check.resolve(verified);await done;assert.deepEqual(h.rendered,[cached,verified]);assert.deepEqual(h.errors,[]);
});
test('overview ignores a late startup result after a newer refresh',async()=>{
 const check=pending(), fresh=[{id:'new'}];let calls=0,signal;
 const h=setup(options=>{calls++;if(calls===1)return[{id:'old',storageChecking:true}];if(calls===2){signal=options.signal;return check.promise;}return fresh;});
 await h.refresh({cached:true});const done=h.deferred.shift()();await h.refresh();
 assert.equal(signal.aborted,true);check.resolve([{id:'old'}]);await done;assert.deepEqual(h.rendered.at(-1),fresh);assert.equal(h.rendered.length,2);
});
test('overview skips a scheduled check superseded before it starts',async()=>{
 let calls=0;const h=setup(()=>{calls++;return[{id:String(calls),storageChecking:true}];});
 await h.refresh({cached:true});await h.refresh();await h.deferred.shift()();assert.equal(calls,2);assert.equal(h.rendered.at(-1)[0].id,'2');
});
test('app-only overview needs no second read and failed checks preserve visible figures',async()=>{
 const h=setup(()=>[{id:'app'}]);await h.refresh({cached:true});assert.equal(h.deferred.length,0);
 const error=Error('unavailable');const f=setup(options=>options.cached?[{storageChecking:true}]:Promise.reject(error));
 await f.refresh({cached:true});await f.deferred.shift()();assert.equal(f.rendered.length,1);assert.deepEqual(f.errors,[error]);
});

test('focus and visibility reuse both a pending first overview and its background check',async()=>{
 const first=pending(),check=pending();let calls=0;
 const h=setup(()=>++calls===1?first.promise:check.promise);
 const opened=h.refresh({cached:true});h.resume();h.resume();assert.equal(calls,1);
 first.resolve([{storageChecking:true}]);await opened;
 h.resume();assert.equal(calls,1,'scheduled validation was restarted');
 const done=h.deferred.shift()();h.resume();h.resume();assert.equal(calls,2,'in-flight validation was restarted');
 check.resolve([{id:'verified'}]);await done;assert.equal(h.rendered.at(-1)[0].id,'verified');
});
test('tab changes and resume reuse verified folder results without rescanning',async()=>{
 const calls=[], verified=[{id:'folder',storageProblem:''}];
 const h=setup(options=>{calls.push(options);return verified;});
 await h.refresh();await h.refresh({cached:true});await h.resume();
 assert.equal(calls.length,1);assert.deepEqual(h.rendered,[verified,verified,verified]);
});
test('expired results stay visible during one background check and mutations bypass cache',async()=>{
 let clock=0; const check=pending(),calls=[],initial=[{id:'folder'}],fresh=[{id:'saved'}];
 const h=setup(options=>{calls.push(options);return calls.length===1?initial:calls.length===2?check.promise:fresh;},{now:()=>clock});
 await h.refresh();clock=60_000;await h.refresh({cached:true});
 assert.equal(calls.length,1);assert.deepEqual(h.rendered.at(-1),initial);
 const done=h.deferred.shift()();await h.refresh({cached:true});h.resume();assert.equal(calls.length,2);
 await h.refresh();assert.equal(calls[1].signal.aborted,true);
 check.resolve([{id:'stale'}]);await done;assert.deepEqual(h.rendered.at(-1),fresh);
 await h.refresh({cached:true});assert.equal(calls.length,3);
});
test('completed background validation is reused on the next tab change',async()=>{
 let calls=0;const h=setup(options=>{calls++;return options.cached?[{storageChecking:true}]:[{id:'checked'}];});
 await h.refresh({cached:true});await h.deferred.shift()();
 await h.refresh({cached:true});assert.equal(calls,2);assert.deepEqual(h.rendered.at(-1),[{id:'checked'}]);
});

test('explicit post-save refresh reports a failed or superseded overview to its caller',async()=>{
 const error=Error('offline'),f=setup(()=>Promise.reject(error));
 await assert.rejects(f.refresh({strict:true}),error);assert.deepEqual(f.errors,[error]);
 const delayed=pending();let calls=0;const h=setup(()=>++calls===1?delayed.promise:[{id:'fresh'}]);
 const stale=h.refresh({strict:true});await h.refresh();delayed.resolve([{id:'stale'}]);
 await assert.rejects(stale,{name:'AbortError'});assert.equal(h.rendered.at(-1)[0].id,'fresh');
});

test('storage mutations outside the overview invalidate recent verified results',async()=>{
 let version=0,calls=0;
 const h=setup(()=>[{id:String(++calls)}],{revision:()=>version});
 await h.refresh();await h.refresh({cached:true});assert.equal(calls,1);
 version++;await h.resume();assert.equal(calls,2);assert.equal(h.rendered.at(-1)[0].id,'2');
});

test('a mutation supersedes a pending cached check even when returning through resume',async()=>{
 let version=0,calls=0;const first=pending();
 const h=setup(()=>++calls===1?first.promise:[{id:'new'}],{revision:()=>version});
 const old=h.refresh({cached:true});version++;await h.resume();
 first.resolve([{id:'old'}]);await old;
 assert.equal(calls,2);assert.deepEqual(h.rendered,[[{id:'new'}]]);
});

test('post-save refresh waits only for summaries and reports their failures before deferring media checks',async()=>{
 const h=setup(options=>options.cached?[{storageChecking:true}]:[{id:'checked'}]);
 await h.refresh({strict:true,summaries:true});assert.equal(h.deferred.length,1);
 assert.equal(h.rendered[0][0].storageChecking,true);await h.deferred.shift()();assert.equal(h.rendered.at(-1)[0].id,'checked');
 const error=Error('summary unavailable'),f=setup(()=>Promise.reject(error));
 await assert.rejects(f.refresh({strict:true,summaries:true}),error);
});

test('overview renders catalog snapshots immediately and verifies videos only in the background',async()=>{
 const calls=[];const h=setup(options=>{calls.push(options);return options.cached?[{id:'old',catalogPending:true}]:[{id:'updated'}];});
 await h.refresh({cached:true});assert.equal(h.rendered[0][0].id,'old');assert.equal(h.rendered[0][0].storageChecking,undefined);
 assert.equal(calls[0].catalogOnly,true);await h.deferred.shift()();assert.equal(calls[1].catalogOnly,undefined);
 assert.equal(h.rendered.at(-1)[0].id,'updated');await h.refresh({cached:true});assert.equal(calls.length,2);
});

test('an unchanged background result does not redraw the list or disturb open menus',async()=>{
 const h=setup(options=>options.cached?[{id:'same',catalogPending:true}]:[{id:'same',storageProblem:'',folderAccessRequired:false}]);
 await h.refresh({cached:true});assert.equal(h.rendered.length,1);
 await h.deferred.shift()();assert.equal(h.rendered.length,1);
});

test('an empty remembered folder is still refreshed to discover externally added figures',async()=>{
 const h=setup(options=>options.cached?Object.assign([],{catalogPending:true}):[{id:'external'}]);
 await h.refresh({cached:true});assert.equal(h.rendered[0].length,0);
 await h.deferred.shift()();assert.equal(h.rendered.at(-1)[0].id,'external');
});
