package com.stepbystep.salsa

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class FolderWriteSessionsTest {
    private class LocalTarget(val file: File) : FolderWriteSessions.Target {
        override fun open(): OutputStream = FileOutputStream(file)
        override fun length(): Long = file.length()
    }

    private fun directory() = Files.createTempDirectory("folder-writes-test").toFile()

    private fun staged(manager: FolderWriteSessions, key: String, target: FolderWriteSessions.Target, bytes: ByteArray): String {
        val token = manager.begin(key, target)
        bytes.asList().chunked(196608).forEach { manager.append(token, it.toByteArray()) }
        return token
    }

    private fun assertNoStaging(directory: File) {
        assertTrue(directory.listFiles()!!.none { it.name.startsWith("saf-") })
    }

    @Test fun writesRealBytesOnlyOnCloseAndHoldsTheTargetLeaseUntilCompletion() {
        val dir = directory()
        val target = LocalTarget(File(dir, "video.mp4").apply { writeText("old bytes") })
        val bytes = ByteArray(900017) { (it % 251).toByte() }
        FolderWriteSessions(dir).use { manager ->
            val token = staged(manager, "folder/video.mp4", target, bytes)
            assertEquals("old bytes", target.file.readText())
            assertThrows(IllegalStateException::class.java) { manager.begin("folder/video.mp4", target) }
            manager.finish(token)
            assertArrayEquals(bytes, target.file.readBytes())
            assertThrows(IllegalStateException::class.java) { manager.finish(token) }
            assertThrows(IllegalStateException::class.java) { manager.append(token, byteArrayOf(1)) }
            assertNoStaging(dir)
            manager.abort(manager.begin("folder/video.mp4", target))
        }
        dir.deleteRecursively()
    }

    @Test fun recreatedManagerCannotOverwriteATargetWhileAnOldCommitDrains() {
        val dir = directory()
        val file = File(dir, "library.json").apply { writeText("old catalog") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CompletableFuture<Unit>()
        val old = FolderWriteSessions(dir)
        val blocked = object : FolderWriteSessions.Target {
            override fun open(): OutputStream {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return FileOutputStream(file)
            }
            override fun length() = file.length()
        }
        val token = staged(old, "catalog", blocked, "old manager commit".toByteArray())
        val commitThread = Thread {
            try { old.finish(token); finished.complete(Unit) }
            catch (error: Throwable) { finished.completeExceptionally(error) }
        }
        commitThread.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            CompletableFuture.runAsync { old.close() }.get(1, TimeUnit.SECONDS)
            // A canonical directory alias must share the same process-wide target lease.
            FolderWriteSessions(File(dir, ".")).use { fresh ->
                val local = LocalTarget(file)
                assertThrows(IllegalStateException::class.java) { fresh.begin("catalog", local) }
                assertEquals(1, dir.listFiles()!!.count { it.name.startsWith("saf-") })
                val independentDir = directory()
                FolderWriteSessions(independentDir).use { independent ->
                    independent.abort(independent.begin("catalog", LocalTarget(File(independentDir, "library.json"))))
                }
                independentDir.deleteRecursively()
                release.countDown()
                finished.get(5, TimeUnit.SECONDS)
                assertEquals("old manager commit", file.readText())
                fresh.finish(staged(fresh, "catalog", local, "new manager commit".toByteArray()))
                assertEquals("new manager commit", file.readText())
                assertNoStaging(dir)
            }
        } finally {
            release.countDown()
            old.close()
            commitThread.join(5000)
            dir.deleteRecursively()
        }
    }

    @Test fun abortBeforeCloseLeavesTheDestinationUntouchedAndStaleTokensCannotChangeANewerWrite() {
        val dir = directory()
        val target = LocalTarget(File(dir, "library.json").apply { writeText("previous catalog") })
        FolderWriteSessions(dir).use { manager ->
            val old = staged(manager, "catalog", target, "discard me".toByteArray())
            manager.abort(old)
            assertEquals("previous catalog", target.file.readText())
            assertThrows(IllegalStateException::class.java) { manager.finish(old) }
            val next = staged(manager, "catalog", target, "new catalog".toByteArray())
            manager.abort(old)
            manager.finish(next)
            assertEquals("new catalog", target.file.readText())
            assertNoStaging(dir)
        }
        dir.deleteRecursively()
    }

    @Test fun closeWinsOverAbortWithoutHoldingTheStateLockDuringProviderIo() {
        val dir = directory()
        val targetFile = File(dir, "library.json").apply { writeText("old catalog") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        val target = object : FolderWriteSessions.Target {
            override fun open(): OutputStream {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return FileOutputStream(targetFile)
            }
            override fun length() = targetFile.length()
        }
        FolderWriteSessions(dir).use { manager ->
            val token = staged(manager, "catalog", target, "complete catalog".toByteArray())
            manager.finishAsync(token) { result.set(it); finished.countDown() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            CompletableFuture.runAsync { manager.abort(token) }.get(1, TimeUnit.SECONDS)
            // An unrelated target is still usable while this provider is blocked.
            val other = LocalTarget(File(dir, "other"))
            manager.abort(manager.begin("other", other))
            assertThrows(IllegalStateException::class.java) { manager.finish(token) }
            assertEquals("old catalog", targetFile.readText())
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertTrue(result.get().isSuccess)
            assertEquals("complete catalog", targetFile.readText())
            assertNoStaging(dir)
        }
        dir.deleteRecursively()
    }

    @Test fun destructionDoesNotBlockCopyAndAlreadyClaimedQueuedCommitsStillFinishWithoutCallbacks() {
        val dir = directory()
        val manager = FolderWriteSessions(dir)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val drained = CountDownLatch(1)
        val callbacks = AtomicInteger()
        val firstFile = File(dir, "library.json")
        val firstTarget = object : FolderWriteSessions.Target {
            override fun open(): OutputStream {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return FileOutputStream(firstFile)
            }
            override fun length() = firstFile.length()
        }
        val secondFile = File(dir, "queued-video.mp4")
        val secondTarget = object : FolderWriteSessions.Target {
            override fun open(): OutputStream = FileOutputStream(secondFile)
            override fun length(): Long = secondFile.length().also { drained.countDown() }
        }
        val first = staged(manager, "catalog", firstTarget, "complete catalog".toByteArray())
        manager.finishAsync(first) { callbacks.incrementAndGet() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val second = staged(manager, "queued", secondTarget, "queued video".toByteArray())
        manager.finishAsync(second) { callbacks.incrementAndGet() }
        manager.abort(second) // Queueing already claimed close, so abort cannot remove its source.
        val pending = staged(manager, "discard", LocalTarget(File(dir, "must-not-exist")), byteArrayOf(1))
        CompletableFuture.runAsync { manager.close() }.get(1, TimeUnit.SECONDS)
        assertThrows(IllegalStateException::class.java) { manager.begin("after-close", secondTarget) }
        assertThrows(IllegalStateException::class.java) { manager.append(pending, byteArrayOf(2)) }
        release.countDown()
        assertTrue(drained.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (dir.listFiles()!!.any { it.name.startsWith("saf-") } && System.nanoTime() < deadline) Thread.yield()
        assertEquals("complete catalog", firstFile.readText())
        assertEquals("queued video", secondFile.readText())
        assertEquals(0, callbacks.get())
        assertFalse(File(dir, "must-not-exist").exists())
        assertNoStaging(dir)
        manager.close()
        dir.deleteRecursively()
    }

    @Test fun permissionFailurePreservesTheOldDestinationAndCleansOnlyItsStaging() {
        val dir = directory()
        val previous = File(dir, "library.json").apply { writeText("old catalog") }
        val unrelated = File(dir, "unrelated").apply { writeText("keep") }
        val denied = object : FolderWriteSessions.Target {
            override fun open(): OutputStream = throw SecurityException("grant revoked")
            override fun length(): Long = error("must not verify unopened target")
        }
        FolderWriteSessions(dir).use { manager ->
            val token = staged(manager, "catalog", denied, "new catalog".toByteArray())
            assertThrows(SecurityException::class.java) { manager.finish(token) }
            assertEquals("old catalog", previous.readText())
            assertEquals("keep", unrelated.readText())
            assertNoStaging(dir)
            manager.abort(manager.begin("catalog", LocalTarget(previous)))
        }
        dir.deleteRecursively()
    }

    @Test fun anAlreadyRunningCompletionCannotBlockManagerDestruction() {
        val dir = directory()
        val manager = FolderWriteSessions(dir)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val token = staged(manager, "target", LocalTarget(File(dir, "target")), "bytes".toByteArray())
        manager.finishAsync(token) {
            assertTrue(it.isSuccess)
            entered.countDown()
            try { check(release.await(5, TimeUnit.SECONDS)) }
            finally { returned.countDown() }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            CompletableFuture.runAsync { manager.close() }.get(1, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            manager.close()
            dir.deleteRecursively()
        }
    }

    @Test fun partialWriteAndAmbiguousCloseFailuresNeverDeleteTheDestination() {
        for (failDuringWrite in listOf(false, true)) {
            val dir = directory()
            val targetFile = File(dir, "library.json").apply { writeText("old catalog") }
            val target = object : FolderWriteSessions.Target {
                override fun open(): OutputStream = object : FilterOutputStream(FileOutputStream(targetFile)) {
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        if (failDuringWrite) {
                            out.write(bytes, offset, minOf(3, length))
                            throw IOException("provider full")
                        }
                        out.write(bytes, offset, length)
                    }
                    override fun close() {
                        out.close()
                        if (!failDuringWrite) throw IOException("ambiguous provider close")
                    }
                }
                override fun length() = targetFile.length()
            }
            FolderWriteSessions(dir).use { manager ->
                val token = staged(manager, "catalog", target, "new catalog".toByteArray())
                assertThrows(IOException::class.java) { manager.finish(token) }
                assertTrue(targetFile.exists())
                assertEquals(if (failDuringWrite) "new" else "new catalog", targetFile.readText())
                assertNoStaging(dir)
                manager.abort(manager.begin("catalog", LocalTarget(targetFile)))
            }
            dir.deleteRecursively()
        }
    }

    @Test fun lengthMismatchFailsAndOversizeBlockCleansTheReceivingSession() {
        val dir = directory()
        val targetFile = File(dir, "target")
        val mismatch = object : FolderWriteSessions.Target {
            override fun open(): OutputStream = FileOutputStream(targetFile)
            override fun length() = targetFile.length() + 1
        }
        FolderWriteSessions(dir).use { manager ->
            val token = staged(manager, "target", mismatch, "bytes".toByteArray())
            assertThrows(IllegalStateException::class.java) { manager.finish(token) }
            assertTrue(targetFile.exists())
            assertNoStaging(dir)
            val another = manager.begin("target", mismatch)
            assertThrows(IllegalArgumentException::class.java) { manager.append(another, ByteArray(FolderWriteSessions.MAX_BLOCK_BYTES + 1)) }
            assertNoStaging(dir)
            manager.abort(manager.begin("target", mismatch))
        }
        dir.deleteRecursively()
    }

    @Test fun concurrentAbortAndCloseEitherPreserveOrCompletelyReplaceTheTarget() {
        val dir = directory()
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(30) { iteration ->
                val target = LocalTarget(File(dir, "target-$iteration").apply { writeText("old") })
                FolderWriteSessions(dir).use { manager ->
                    val token = staged(manager, "target", target, "complete new bytes".toByteArray())
                    val start = CountDownLatch(1)
                    val finish = executor.submit(Callable { start.await(); runCatching { manager.finish(token) } })
                    val abort = executor.submit(Callable { start.await(); manager.abort(token) })
                    start.countDown()
                    val outcome = finish.get(5, TimeUnit.SECONDS)
                    abort.get(5, TimeUnit.SECONDS)
                    assertEquals(if (outcome.isSuccess) "complete new bytes" else "old", target.file.readText())
                    assertNoStaging(dir)
                }
            }
        } finally { executor.shutdownNow(); dir.deleteRecursively() }
    }
}
