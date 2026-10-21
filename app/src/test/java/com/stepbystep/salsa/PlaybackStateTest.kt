package com.stepbystep.salsa

import org.junit.Assert.*
import org.junit.Test

class PlaybackStateTest {
    @Test fun loopAndABCannotOverlap() {
        val s=PlaybackState();s.toggleLoop();assertTrue(s.loop)
        s.markA(1000);assertFalse(s.loop);s.markB(1100);assertNull(s.b)
        s.markB(2500);assertNull(s.target(2499));assertEquals(1000,s.target(2500));assertEquals(1000,s.target(0,true))
        s.toggleLoop();assertTrue(s.loop);assertNull(s.a);assertNull(s.b);assertEquals(0,s.target(5000,true))
    }
    @Test fun switchingVideoPreservesLearningPreferencesAndClearsMarks() {
        val s=PlaybackState();s.speed=.5f;s.crop=true;s.markA(1000);s.markB(2000);s.newVideo()
        assertEquals(.5f,s.speed);assertTrue(s.crop);assertNull(s.a);assertNull(s.b)
        s.toggleLoop();s.newVideo();assertTrue(s.loop)
    }
    @Test fun changingAInvalidatesBAndInvalidBIsIgnored() {
        val s=PlaybackState();s.markB(1000);assertNull(s.b);s.markA(-100);assertEquals(0,s.a)
        s.markB(2000);s.markA(3000);assertNull(s.b);s.markB(2900);assertNull(s.b)
    }
}
