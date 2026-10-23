package com.stepbystep.salsa

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IncomingSessionTest {
    @Test fun claimMakesTheCurrentCommitNonCancellableUntilCompletion() {
        IncomingSession<String>().use { session ->
            session.ready()
            val ticket = session.offer("apply prepared input")!!
            assertTrue(session.claim(ticket.id))
            assertFalse(session.claim(ticket.id))
            assertFalse(session.cancel(ticket.id))
            assertTrue(session.active(ticket.id))
            assertNull(session.offer("cannot replace committed input"))
            assertTrue(session.finish(ticket.id))
            assertFalse(session.active(ticket.id))
            assertFalse(session.claim(ticket.id))
        }
    }

    @Test fun cancellationAndStaleIdsCannotClaimOrCancelTheNextInput() {
        IncomingSession<String>().use { session ->
            session.ready()
            val cancelled = session.offer("cancel before commit")!!
            assertTrue(session.cancel(cancelled.id))
            assertFalse(session.claim(cancelled.id))
            val next = session.offer("new input")!!
            assertFalse(session.claim(cancelled.id))
            assertFalse(session.cancel(cancelled.id))
            assertTrue(session.active(next.id))
            assertTrue(session.claim(next.id))
            session.close()
            assertFalse(session.active(next.id))
            assertFalse(session.claim(next.id))
            assertFalse(session.finish(next.id))
        }
    }

    @Test fun concurrentClaimAndCancellationHaveExactlyOneWinner() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(40) {
                IncomingSession<String>().use { session ->
                    session.ready()
                    val ticket = session.offer("racing input")!!
                    val start = CountDownLatch(1)
                    val claim = executor.submit(Callable { start.await(); session.claim(ticket.id) })
                    val cancel = executor.submit(Callable { start.await(); session.cancel(ticket.id) })
                    start.countDown()
                    val claimed = claim.get(5, TimeUnit.SECONDS)
                    val cancelled = cancel.get(5, TimeUnit.SECONDS)
                    assertNotEquals(claimed, cancelled)
                    assertEquals(claimed, session.active(ticket.id))
                    if (claimed) assertTrue(session.finish(ticket.id))
                    else assertFalse(session.claim(ticket.id))
                }
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun coldStartKeepsTheFirstActionAndDispatchesExactlyOnce() {
        IncomingSession<String>().use { session ->
            val first = session.offer("first")!!
            assertFalse(first.dispatchNow)
            assertNull(session.offer("second"))
            assertTrue(session.active(first.id))
            val dispatched = session.ready()!!
            assertEquals(first.id, dispatched.id)
            assertEquals("first", dispatched.action)
            assertTrue(dispatched.dispatchNow)
            assertNull(session.ready())
            assertNull(session.offer("still busy"))
            assertTrue(session.finish(first.id))
            assertFalse(session.active(first.id))
        }
    }

    @Test fun warmStartReservesBeforeAnAsynchronousScreenCheck() {
        IncomingSession<String>().use { session ->
            assertNull(session.ready())
            val checking = session.offer("checking UI")!!
            assertTrue(checking.dispatchNow)
            assertNull(session.offer("racing intent"))
            assertNull(session.ready())
            assertTrue(session.cancel(checking.id))
            val next = session.offer("next")!!
            assertTrue(next.dispatchNow)
            assertTrue(next.id > checking.id)
        }
    }

    @Test fun cancelledAndOlderCallbacksCannotAffectTheNewerAction() {
        IncomingSession<String>().use { session ->
            session.ready()
            val old = session.offer("old")!!
            assertTrue(session.cancel(old.id))
            val next = session.offer("new")!!
            assertFalse(session.active(old.id))
            assertFalse(session.finish(old.id))
            assertFalse(session.cancel(old.id))
            assertTrue(session.active(next.id))
            assertTrue(session.finish(next.id))
            assertFalse(session.finish(next.id))
        }
    }

    @Test fun documentReloadWaitsAgainWithoutReplayingTheClaimedAction() {
        IncomingSession<String>().use { session ->
            session.ready()
            val login = session.offer("login")!!
            session.notReady()
            assertNull(session.ready())
            assertTrue(session.finish(login.id))
            session.notReady()
            val next = session.offer("after reload")!!
            assertFalse(next.dispatchNow)
            assertEquals(next.id, session.ready()!!.id)
            assertNull(session.ready())
        }
    }

    @Test fun destructionInvalidatesPendingAndDispatchedWork() {
        for (ready in listOf(false, true)) {
            val session = IncomingSession<String>()
            if (ready) session.ready()
            val ticket = session.offer("incoming")!!
            session.close()
            assertFalse(session.active(ticket.id))
            assertFalse(session.finish(ticket.id))
            assertFalse(session.cancel(ticket.id))
            assertNull(session.ready())
            assertNull(session.offer("after close"))
            session.close()
        }
    }
}
