(() => {
  if(!window.AndroidPlatform)return;
  let full=false;
  addEventListener('DOMContentLoaded',()=>{
    const heading=document.querySelector('.overview > .heading');
    if(heading){
      const measure=()=>document.documentElement.style.setProperty('--brand-header-height',`${heading.getBoundingClientRect().height}px`);
      new ResizeObserver(measure).observe(heading);measure();
    }
    document.getElementById('offlineStatus').textContent='Offline bereit · Android';
    document.getElementById('offlineDetails').textContent='Die Oberfläche ist im APK enthalten und startet ohne Internet. Cloud-Funktionen benötigen Internet.';
    document.getElementById('appVersion').textContent='Step by Step · Android 0.7.7';
    document.querySelector('#persistStorage').textContent='App-Speicher geschützt';
    document.querySelector('#persistStorage').onclick=()=>document.getElementById('persistenceResult').textContent='Android verwaltet den privaten App-Speicher. Eine Deinstallation oder „App-Daten löschen“ entfernt ihn. Nutze Backups.';
    document.addEventListener('click',event=>{
      if(event.target.closest('#rotatePlayer')){event.preventDefault();event.stopImmediatePropagation();const b=document.getElementById('rotatePlayer');const landscape=b.getAttribute('aria-pressed')!=='true';b.setAttribute('aria-pressed',String(landscape));AndroidPlatform.rotate(landscape);}
      if(event.target.closest('#fullscreen')){event.preventDefault();event.stopImmediatePropagation();full=!full;AndroidPlatform.fullscreen(full);}
      if(event.target.closest('#playerBack')){full=false;AndroidPlatform.fullscreen(false);AndroidPlatform.rotate(false);}
    },true);
    const refreshWake=()=>AndroidPlatform.keepAwake([...document.querySelectorAll('video')].some(v=>!v.paused));
    document.addEventListener('play',refreshWake,true);document.addEventListener('pause',refreshWake,true);document.addEventListener('ended',refreshWake,true);
    addEventListener('nativepause',()=>{document.querySelectorAll('video').forEach(v=>v.pause());AndroidPlatform.keepAwake(false);});
  });
})();
