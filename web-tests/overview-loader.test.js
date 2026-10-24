import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createOverviewLoader } from '../app/src/main/assets/overview-loader.js';
const pending = () => { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b; }); return {promise,resolve,reject}; };
function setup(list) {
 const rendered=[], errors=[], deferred=[];
 const loader=createOverviewLoader({list,render:x=>rendered.push(x),failed:x=>errors.push(x),defer:x=>deferred.push(x)});
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
test('returning to an idle overview renders summaries before revalidating folders',async()=>{
 const calls=[];const h=setup(options=>{calls.push(options);return[{id:'app'}];});
 await h.refresh();await h.resume();assert.equal(calls.length,2);assert.equal(calls[1].cached,true);
});
