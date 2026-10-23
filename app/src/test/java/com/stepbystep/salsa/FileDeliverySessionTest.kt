package com.stepbystep.salsa

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FileDeliverySessionTest {
    private fun directory() = Files.createTempDirectory("file-delivery-test").toFile().apply { deleteOnExit() }
    private fun prepared(session: FileDeliverySession, bytes: ByteArray = "original ZIP bytes".toByteArray()): FileDeliverySession.Prepared {
        assertTrue(session.begin("Figur.zip"))
        assertTrue(session.append(bytes))
        return session.prepare("application/zip")!!
    }

    @Test fun cancelledPickerKeepsTheSamePreparedArchiveAndRejectsDuplicates() {
        val dir = directory()
        FileDeliverySession(dir).use { session ->
            val first = prepared(session)
            assertFalse(session.begin("other.zip"))
            assertNull(session.prepare("application/zip"))
            session.abortReceiving() // A late bridge abort must not destroy the ready archive.
            assertTrue(session.requestSave(first.id))
            assertFalse(session.requestSave(first.id))
            assertTrue(session.cancelPicker(first.id))
            assertEquals(first, session.prepared())
            assertEquals("original ZIP bytes", first.file.readText())
            assertTrue(session.requestSave(first.id))
            val target = ByteArrayOutputStream()
            assertEquals(FileDeliverySession.Result.Saved, session.save(first.id, { target }))
            assertEquals("original ZIP bytes", target.toString("UTF-8"))
            assertFalse(session.begin("too-early.zip"))
            session.releaseSaved(first.id)
            assertFalse(first.file.exists())
            assertTrue(session.begin("next.zip"))
        }
        dir.deleteRecursively()
    }

    @Test fun targetWriteAndCloseFailuresAllowRetryWithoutPreparingAgain() {
        val dir = directory()
        FileDeliverySession(dir).use { session ->
            val first = prepared(session)
            assertTrue(session.requestSave(first.id))
            assertTrue(session.save(first.id, { throw IOException("cannot open target") }) is FileDeliverySession.Result.Failed)
            assertEquals(first, session.prepared())
            for (failOnClose in listOf(false, true)) {
                assertTrue(session.requestSave(first.id))
                val result = session.save(first.id, { object : OutputStream() {
                    override fun write(b: Int) { if (!failOnClose) throw IOException("full") }
                    override fun close() { if (failOnClose) throw IOException("close failed") }
                } })
                assertTrue(result is FileDeliverySession.Result.Failed)
                assertEquals(first, session.prepared())
                assertEquals("original ZIP bytes", first.file.readText())
            }
            assertTrue(session.requestSave(first.id))
            val target = ByteArrayOutputStream()
            assertEquals(FileDeliverySession.Result.Saved, session.save(first.id, { target }))
            session.releaseSaved(first.id)
        }
        dir.deleteRecursively()
    }

    @Test fun progressDoesNotPublishSuccessBeforeTheDestinationCloses() {
        val dir = directory()
        FileDeliverySession(dir).use { session ->
            val bytes = ByteArray(140000) { (it % 251).toByte() }
            val first = prepared(session, bytes)
            assertTrue(session.requestSave(first.id))
            val closing = CountDownLatch(1)
            val finishClose = CountDownLatch(1)
            val percentages = java.util.concurrent.CopyOnWriteArrayList<Int>()
            val target = object : ByteArrayOutputStream() {
                override fun close() { closing.countDown(); check(finishClose.await(5, TimeUnit.SECONDS)) }
            }
            val copy = CompletableFuture.supplyAsync { session.save(first.id, { target }, percentages::add) }
            assertTrue(closing.await(5, TimeUnit.SECONDS))
            assertFalse(copy.isDone)
            assertNull(session.prepared())
            assertFalse(session.begin("parallel.zip"))
            finishClose.countDown()
            assertEquals(FileDeliverySession.Result.Saved, copy.get(5, TimeUnit.SECONDS))
            assertArrayEquals(bytes, target.toByteArray())
            assertEquals(0, percentages.first())
            assertEquals(100, percentages.last())
            assertEquals(percentages.distinct().sorted(), percentages)
            session.releaseSaved(first.id)
        }
        dir.deleteRecursively()
    }

    @Test fun destructionDoesNotWaitForBlockedTargetAndSuppressesLateSuccess() {
        val dir = directory()
        val session = FileDeliverySession(dir)
        val first = prepared(session)
        assertTrue(session.requestSave(first.id))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val copy = CompletableFuture.supplyAsync {
            session.save(first.id, { object : OutputStream() {
                override fun write(b: Int) {}
                override fun write(b: ByteArray, off: Int, len: Int) {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }
            } })
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        // close must be independent of the monitor held by provider I/O.
        CompletableFuture.runAsync { session.close() }.get(1, TimeUnit.SECONDS)
        assertFalse(session.begin("after-destroy.zip"))
        release.countDown()
        assertEquals(FileDeliverySession.Result.Cancelled, copy.get(5, TimeUnit.SECONDS))
        assertFalse(first.file.exists())
        dir.deleteRecursively()
    }

    @Test fun explicitDiscardAndStalePickerResultsCannotAffectANewerArchive() {
        val dir = directory()
        FileDeliverySession(dir).use { session ->
            val old = prepared(session)
            session.discard(old.id)
            assertFalse(old.file.exists())
            val current = prepared(session)
            assertTrue(session.requestSave(current.id))
            assertFalse(session.cancelPicker(old.id))
            session.discard(old.id)
            var staleTargetOpened = false
            assertEquals(FileDeliverySession.Result.Cancelled, session.save(old.id, { staleTargetOpened = true; ByteArrayOutputStream() }))
            assertFalse(staleTargetOpened)
            assertTrue(session.cancelPicker(current.id))
            assertEquals(current, session.prepared())
        }
        assertTrue(dir.listFiles()!!.isEmpty())
        dir.deleteRecursively()
    }

    @Test fun sharedUriBytesSurviveActivityExitAndExpireWithoutDeletingOtherFiles() {
        val dir = directory()
        var clock = System.currentTimeMillis()
        val unrelated = File(dir, "unrelated").apply { writeText("keep") }
        val session = FileDeliverySession(dir) { clock }
        val first = prepared(session)
        assertTrue(session.handedOff(first.id))
        session.close()
        assertEquals("original ZIP bytes", first.file.readText())
        clock += FileDeliverySession.SHARE_RETENTION_MS + 1
        FileDeliverySession(dir) { clock }.close()
        assertFalse(first.file.exists())
        assertEquals("keep", unrelated.readText())
        dir.deleteRecursively()
    }

    @Test fun oversizeBridgeBlockAbortsOnlyReceivingBytesAndCloseCleansOtherPhases() {
        val dir = directory()
        FileDeliverySession(dir).use { session ->
            assertTrue(session.begin("bad.zip"))
            assertFalse(session.append(ByteArray(FileDeliverySession.MAX_BLOCK_BYTES + 1)))
            assertTrue(dir.listFiles()!!.isEmpty())
            val first = prepared(session)
            assertTrue(session.requestSave(first.id))
        }
        assertTrue(dir.listFiles()!!.isEmpty())
        dir.deleteRecursively()
    }
}
