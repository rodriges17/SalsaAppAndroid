(() => {
  if(!window.AndroidPlatform)return;
  let full=false;
  window.stepNativePlayer={reset(){full=false;AndroidPlatform.fullscreen(false);AndroidPlatform.rotate(false);}};
  addEventListener('DOMContentLoaded',()=>{
    for(const [selector,variable] of [['.overview > .heading','--brand-header-height'],['.page-intro','--overview-intro-height'],['.overview-filters','--overview-filters-height']]){
      const row=document.querySelector(selector);if(!row)continue;
      const measure=()=>document.documentElement.style.setProperty(variable,`${row.getBoundingClientRect().height}px`);
      new ResizeObserver(measure).observe(row);measure();
    }
    document.getElementById('offlineStatus').textContent='Offline bereit · Android';
    document.getElementById('offlineDetails').textContent='Die Oberfläche ist im APK enthalten und startet ohne Internet. Cloud-Funktionen benötigen Internet.';
    document.getElementById('appVersion').textContent='Step by Step · Android 0.7.13';
    document.querySelector('#persistStorage').textContent='App-Speicher geschützt';
    document.querySelector('#persistStorage').onclick=()=>document.getElementById('persistenceResult').textContent='Android verwaltet den privaten App-Speicher. Eine Deinstallation oder „App-Daten löschen“ entfernt ihn. Nutze Backups.';
    document.addEventListener('click',event=>{
      if(event.target.closest('#rotatePlayer')){const b=document.getElementById('rotatePlayer');AndroidPlatform.rotate(b.getAttribute('aria-pressed')!=='true');}
      if(event.target.closest('#fullscreen')){event.preventDefault();event.stopImmediatePropagation();full=!full;document.getElementById('playerScreen').classList.toggle('is-native-fullscreen',full);AndroidPlatform.fullscreen(full);}
      if(event.target.closest('#playerBack'))window.stepNativePlayer.reset();
    },true);
    const refreshWake=()=>AndroidPlatform.keepAwake([...document.querySelectorAll('video')].some(v=>!v.paused));
    document.addEventListener('play',refreshWake,true);document.addEventListener('pause',refreshWake,true);document.addEventListener('ended',refreshWake,true);
    addEventListener('nativepause',()=>{document.querySelectorAll('video').forEach(v=>v.pause());AndroidPlatform.keepAwake(false);});
  });
})();
