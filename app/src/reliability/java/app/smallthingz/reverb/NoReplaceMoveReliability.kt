package app.smallthingz.reverb

import android.content.Context
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import org.json.JSONObject
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Returns true only after real JNI success; an actual unsupported mount is reported separately. */
internal fun verifyNoReplaceMovePrimitive(context: Context, report: StringBuilder): Boolean {
    check(context.packageName == "app.smallthingz.reverb.reliability")
    check(Looper.myLooper() != Looper.getMainLooper())
    check(!readDurableCaptureIntentPreferences(getRecorderPreferences(context)).enabled)
    val run = UUID.randomUUID().toString()
    val root = File(context.filesDir, "no-replace-qa-$run")
    check(root.mkdir()) { "No-replace fixture root already exists" }
    val rootIdentity = Os.lstat(root.path)
    val owned = linkedMapOf<File, StructStat>()
    fun event(kind: String, values: JSONObject = JSONObject()) {
        report.append(kind).append(' ').append(values.put("run", run)).append('\n')
    }
    fun rootCurrent() {
        val actual = Os.lstat(root.path)
        check(actual.st_dev == rootIdentity.st_dev && actual.st_ino == rootIdentity.st_ino &&
            OsConstants.S_ISDIR(actual.st_mode)) { "Fixture root identity changed" }
    }
    fun absent(file: File): Boolean = try {
        Os.lstat(file.path)
        false
    } catch (failure: ErrnoException) {
        if (failure.errno != OsConstants.ENOENT) throw failure
        true
    }
    fun sameObject(expected: StructStat, file: File) {
        val actual = Os.lstat(file.path)
        check(actual.st_dev == expected.st_dev && actual.st_ino == expected.st_ino &&
            actual.st_mode == expected.st_mode && actual.st_size == expected.st_size) {
            "Fixture object changed: ${file.name}"
        }
    }
    fun create(name: String, bytes: ByteArray): File = File(root, name).also {
        rootCurrent()
        check(it.createNewFile())
        it.writeBytes(bytes)
        owned[it] = Os.lstat(it.path)
    }
    event("NOREPLACE_PRIMITIVE_BEGIN", JSONObject().put("root", root.path).put("device", rootIdentity.st_dev))
    // No finally cleanup: failure or unsupported capability leaves this run's evidence intact.
    val expected = ByteArray(64) { (it * 3).toByte() }
    val source = create("source-é-\uD83C\uDFB5.bin", expected)
    val destination = File(root, "destination-\uD83D\uDE80-é.bin")
    val sourceIdentity = owned.getValue(source)
    check(absent(destination))
    try {
        moveFileWithoutOverwrite(source, destination)
    } catch (unsupported: NoReplaceMoveUnsupported) {
        rootCurrent()
        sameObject(sourceIdentity, source)
        check(source.readBytes().contentEquals(expected) && absent(destination))
        event("NOREPLACE_PRIMITIVE_UNSUPPORTED", JSONObject().put("native_success", false)
            .put("source_unchanged", true).put("destination_absent", true)
            .put("reason", unsupported.message).put("evidence_retained", true))
        return false
    }
    rootCurrent()
    check(absent(source)) { "Native success left the source name present" }
    sameObject(sourceIdentity, destination)
    check(destination.readBytes().contentEquals(expected))
    owned.remove(source)
    owned[destination] = Os.lstat(destination.path)
    event("NOREPLACE_PRIMITIVE_CASE", JSONObject().put("name", "supplementary-unicode-success")
        .put("source_absent", true).put("destination_bytes", expected.size).put("same_inode", true))

    val collisionSource = create("collision-source.bin", expected)
    val collisionSourceIdentity = owned.getValue(collisionSource)
    fun collision(target: File, kind: String, verifyTarget: () -> Unit) {
        event("NOREPLACE_PRIMITIVE_ATTEMPT", JSONObject().put("name", kind))
        rootCurrent()
        try {
            moveFileWithoutOverwrite(collisionSource, target)
            error("No-replace unexpectedly replaced $kind")
        } catch (_: FileAlreadyExistsException) {
            // Only a real native EEXIST is accepted, never an injected or unsupported result.
        }
        rootCurrent()
        sameObject(collisionSourceIdentity, collisionSource)
        check(collisionSource.readBytes().contentEquals(expected))
        sameObject(owned.getValue(target), target)
        verifyTarget()
        event("NOREPLACE_PRIMITIVE_CASE", JSONObject().put("name", kind)
            .put("source_unchanged", true).put("destination_unchanged", true))
    }
    val foreign = byteArrayOf(11, 22, 33, 44, 55)
    val regular = File(root, "occupied.bin")
    check(absent(regular)) // Allocator observation precedes the competing fixture writer.
    create(regular.name, foreign)
    collision(regular, "regular-collision") { check(regular.readBytes().contentEquals(foreign)) }
    val directory = File(root, "occupied-directory")
    check(directory.mkdir())
    owned[directory] = Os.lstat(directory.path)
    collision(directory, "directory-collision") { check(directory.list()?.isEmpty() == true) }
    val link = File(root, "occupied-link")
    val missingTarget = File(root, "missing-link-target")
    check(absent(link) && absent(missingTarget))
    val linkCreated = try {
        Os.symlink(missingTarget.name, link.path)
        true
    } catch (failure: ErrnoException) {
        if (failure.errno !in listOf(OsConstants.EOPNOTSUPP, OsConstants.ENOSYS, OsConstants.EPERM)) throw failure
        check(absent(link) && absent(missingTarget))
        event("NOREPLACE_PRIMITIVE_UNQUALIFIED", JSONObject().put("name", "dangling-link-collision")
            .put("reason", failure.message))
        false
    }
    if (linkCreated) {
        owned[link] = Os.lstat(link.path)
        check(OsConstants.S_ISLNK(owned.getValue(link).st_mode))
        collision(link, "dangling-link-collision") {
            check(Os.readlink(link.path) == missingTarget.name && absent(missingTarget))
        }
    }
    val workers = Executors.newFixedThreadPool(2)
    var raceFailure: Throwable? = null
    try {
        repeat(8) { round ->
            rootCurrent()
            val raceSource = create("race-$round-source.bin", expected)
            val raceIdentity = owned.getValue(raceSource)
            val raceDestination = File(root, "race-$round-destination.bin")
            check(absent(raceDestination))
            val start = CyclicBarrier(2)
            event("NOREPLACE_PRIMITIVE_RACE_BEGIN", JSONObject().put("round", round))
            val creator = workers.submit<StructStat?> {
                start.await(10, TimeUnit.SECONDS)
                val descriptor = try {
                    Os.open(raceDestination.path, OsConstants.O_CREAT or OsConstants.O_EXCL or
                        OsConstants.O_WRONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW, 384)
                } catch (failure: ErrnoException) {
                    if (failure.errno == OsConstants.EEXIST) return@submit null
                    throw failure
                }
                withOwnedResource(descriptor, { Os.close(it) }) { owner ->
                    var written = 0
                    while (written < foreign.size) {
                        val count = Os.write(owner, foreign, written, foreign.size - written)
                        check(count > 0)
                        written += count
                    }
                    Os.fsync(owner)
                    Os.fstat(owner)
                }
            }
            val mover = workers.submit<Boolean> {
                start.await(10, TimeUnit.SECONDS)
                try {
                    moveFileWithoutOverwrite(raceSource, raceDestination)
                    true
                } catch (_: FileAlreadyExistsException) {
                    false
                }
            }
            // Collect BOTH terminal outcomes before interpreting either; a worker failure preserves evidence.
            val createdResult = runCatching { creator.get(30, TimeUnit.SECONDS) }
            val movedResult = runCatching { mover.get(30, TimeUnit.SECONDS) }
            val creatorFailure = createdResult.exceptionOrNull()
            val moverFailure = movedResult.exceptionOrNull()
            if (creatorFailure != null) {
                if (moverFailure != null && moverFailure !== creatorFailure) creatorFailure.addSuppressed(moverFailure)
                throw creatorFailure
            }
            val created = createdResult.getOrThrow()
            val moved = movedResult.getOrThrow()
            check((created != null) != moved) { "Expected exactly one race winner in round $round" }
            rootCurrent()
            if (created != null) {
                sameObject(created, raceDestination)
                check(raceDestination.readBytes().contentEquals(foreign))
                sameObject(raceIdentity, raceSource)
                check(raceSource.readBytes().contentEquals(expected))
            } else {
                check(absent(raceSource))
                sameObject(raceIdentity, raceDestination)
                check(raceDestination.readBytes().contentEquals(expected))
                owned.remove(raceSource)
            }
            owned[raceDestination] = Os.lstat(raceDestination.path)
            event("NOREPLACE_PRIMITIVE_RACE", JSONObject().put("round", round)
                .put("creator_won", created != null).put("move_won", moved).put("bytes_verified", true))
        }
    } catch (failure: Throwable) {
        raceFailure = failure
        throw failure
    } finally {
        workers.shutdown()
        try {
            check(workers.awaitTermination(30, TimeUnit.SECONDS)) { "Owned race workers did not terminate; preserve evidence" }
        } catch (fenceFailure: Throwable) {
            if (raceFailure != null) {
                if (fenceFailure !== raceFailure) raceFailure.addSuppressed(fenceFailure)
            } else throw fenceFailure
        }
    }
    rootCurrent()
    check(root.list()?.toSet() == owned.keys.map { it.name }.toSet()) { "Unexpected fixture membership" }
    event("NOREPLACE_PRIMITIVE_VERIFIED", JSONObject().put("native_success", true)
        .put("dangling_link_collision_qualified", linkCreated))
    // Only these current-run, identity-checked direct children are removed after all assertions pass.
    for ((file, identity) in owned) {
        rootCurrent()
        sameObject(identity, file)
        Os.remove(file.path)
    }
    rootCurrent()
    check(root.list()?.isEmpty() == true)
    Os.remove(root.path)
    event("NOREPLACE_PRIMITIVE_DONE", JSONObject().put("native_success", true)
        .put("owned_root_removed", true).put("dangling_link_collision_qualified", linkCreated)
        .put("concurrent_rounds_verified", 8))
    return true
}
