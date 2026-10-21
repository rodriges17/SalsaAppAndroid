package com.stepbystep.salsa

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.max
import kotlin.math.min

/** TextureView keeps the video's aspect ratio in both fit and crop without stretching. */
class NativePlayer(private val activity:Activity,private val stage:FrameLayout) {
    private val texture=TextureView(activity)
    private val state=PlaybackState()
    private val handler=Handler(Looper.getMainLooper())
    private var media:MediaPlayer?=null
    private var source:File?=null
    private var ready=false
    private var closed=false
    private var full=false
    private var seek:SeekBar?=null
    private var label:TextView?=null
    private var dragging=false
    private var surface:Surface?=null
    val loop get()=state.loop
    private val ticker=object:Runnable {override fun run(){if(closed)return;val mp=media;if(ready&&mp!=null)runCatching{
        val pos=mp.currentPosition;val duration=mp.duration
        if(!dragging&&duration>0)seek?.progress=(pos.toLong()*1000/duration).toInt()
        label?.text="${NativeActivity.formatTime(pos)} / ${NativeActivity.formatTime(duration)}"+(state.a?.let{"  · A ${NativeActivity.formatTime(it)} · B ${state.b?.let(NativeActivity::formatTime)?:"–"}"}?:"")
        if(mp.isPlaying)state.target(pos)?.let{mp.seekTo(it)}
    };handler.postDelayed(this,100)}}
    init {
        stage.addView(texture,FrameLayout.LayoutParams(-1,-1))
        texture.surfaceTextureListener=object:TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t:SurfaceTexture,w:Int,h:Int){surface=Surface(t);prepare()}
            override fun onSurfaceTextureSizeChanged(t:SurfaceTexture,w:Int,h:Int){transform()}
            override fun onSurfaceTextureDestroyed(t:SurfaceTexture):Boolean{pause();releaseMedia();surface?.release();surface=null;return true}
            override fun onSurfaceTextureUpdated(t:SurfaceTexture){}
        }
        handler.post(ticker)
    }
    fun attachTimeline(bar:SeekBar,time:TextView){seek=bar;label=time;bar.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
        override fun onStartTrackingTouch(b:SeekBar?){dragging=true}
        override fun onStopTrackingTouch(b:SeekBar?){if(ready)media?.let{it.seekTo((it.duration.toLong()*bar.progress/1000).toInt())};dragging=false}
        override fun onProgressChanged(b:SeekBar?,progress:Int,fromUser:Boolean){}
    })}
    fun load(file:File){source=file;state.newVideo();prepare()}
    private fun releaseMedia(){ready=false;media?.let{runCatching{it.reset();it.release()}};media=null}
    private fun prepare(){
        val file=source?:return;val target=surface?:return;if(closed)return
        releaseMedia();label?.text="Video wird geladen …"
        try {
            val mp=MediaPlayer();media=mp;mp.setSurface(target);mp.setDataSource(file.absolutePath)
            mp.setOnPreparedListener{if(closed||media!==it)return@setOnPreparedListener;ready=true;runCatching{it.playbackParams=it.playbackParams.setSpeed(state.speed);it.pause()};transform();label?.text="Bereit · ▶ zum Abspielen"}
            mp.setOnVideoSizeChangedListener{_,_,_->transform()}
            mp.setOnCompletionListener{state.target(it.currentPosition,true)?.let{position->it.seekTo(position);it.start()}?:wake(false)}
            mp.setOnErrorListener{_,_,_->ready=false;wake(false);Toast.makeText(activity,"Video kann nicht abgespielt werden. Bitte bearbeiten und ersetzen.",Toast.LENGTH_LONG).show();true}
            mp.prepareAsync()
        }catch(_:Exception){releaseMedia();Toast.makeText(activity,"Videodatei konnte nicht geöffnet werden",Toast.LENGTH_LONG).show()}
    }
    private fun transform(){val mp=media?:return;if(!ready||texture.width==0||texture.height==0||mp.videoWidth==0||mp.videoHeight==0)return
        val vw=texture.width.toFloat();val vh=texture.height.toFloat();val sx=vw/mp.videoWidth;val sy=vh/mp.videoHeight
        val factor=if(state.crop)max(sx,sy)else min(sx,sy)
        texture.setTransform(Matrix().apply{setScale(mp.videoWidth*factor/vw,mp.videoHeight*factor/vh,vw/2,vh/2)})
    }
    private fun wake(enabled:Boolean){if(enabled)activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
    fun toggle(){if(!ready)return;media?.let{if(it.isPlaying)pause()else{it.start();wake(true)}}}
    fun pause(){if(ready)runCatching{media?.pause()};wake(false)}
    fun rewind(){if(ready)media?.let{it.seekTo((it.currentPosition-5000).coerceAtLeast(0))}}
    fun speed(value:Float){state.speed=value;if(ready)media?.let{mp->val playing=mp.isPlaying;runCatching{mp.playbackParams=mp.playbackParams.setSpeed(value);if(!playing)mp.pause()}}}
    fun toggleCrop(){state.crop=!state.crop;transform()}
    fun toggleLoop(){state.toggleLoop()}
    fun markA(){if(ready)state.markA(media!!.currentPosition)}
    fun markB(){if(ready)state.markB(media!!.currentPosition)}
    fun clearAB(){state.clearAB()}
    fun rotate(){activity.requestedOrientation=if(activity.requestedOrientation==ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
    fun fullscreen(){full=!full;activity.window.decorView.systemUiVisibility=if(full)View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else 0}
    fun close(){closed=true;handler.removeCallbacks(ticker);releaseMedia();surface?.release();surface=null;source=null;wake(false);activity.window.decorView.systemUiVisibility=0}
}
