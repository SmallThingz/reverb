package app.smallthingz.reverb

import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.RejectedExecutionException
import org.junit.Assert.*
import org.junit.Test

class RecordingOperationFailureTest {
    @Test fun programmingFailuresSurviveUncertainOutputWrappers() {
        for (failure in listOf(NullPointerException("filename"), IllegalStateException("save invariant"),
                IllegalArgumentException("invalid internal output name"), ArithmeticException("duration"))) {
            assertTrue(unexpectedRecordingOperationFailure(failure))
            assertTrue(unexpectedRecordingOperationFailure(IOException("Publication uncertain", failure)))
        }
    }

    @Test fun failedSaveAndNameExceptionsAreIncidentsIncludingOrdinaryIo() {
        for (failure in listOf(IOException("Storage unavailable"), SecurityException("Permission denied"),
                RejectedExecutionException("Admission full"), InvalidDurableSaveManifest("Checksum mismatch"),
                IOException("Unable to verify MediaStore publication postcondition: content://media/external/audio/media/1"))) {
            assertTrue(unexpectedRecordingOperationFailure(failure))
            assertTrue(unexpectedRecordingOperationFailure(IOException("Operation failed", failure)))
        }
    }

    @Test fun cancellationAndKnownLifecycleRetirementStayResults() {
        for (failure in listOf(CancellationException("Cancelled"),
                DurableSaveLifetimeRejectedException(DurableSaveLifetimeRejection.SERVICE_STOPPING))) {
            assertFalse(unexpectedRecordingOperationFailure(failure))
            assertFalse(unexpectedRecordingOperationFailure(IOException("Operation failed", failure)))
        }
    }

    @Test fun cyclicAndExcessiveCauseChainsAreBounded() {
        val first = IOException("first")
        val second = IOException("second", first)
        first.initCause(second)
        assertTrue(unexpectedRecordingOperationFailure(first))
        var deep: Throwable = IOException("storage")
        repeat(100) { deep = IOException("wrapper", deep) }
        assertTrue(unexpectedRecordingOperationFailure(deep))
    }
}
