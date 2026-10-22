package app.smallthingz.reverb

import org.junit.Assert.fail
import org.junit.Test

class NativeProbeAdmissionTest {
    @Test fun actualUiOnlyHostIsAccepted() {
        requireNativeMathProbeHost("app.smallthingz.reverb.nativeparity.reliability", true)
    }

    @Test fun recorderApplicationInUiOnlyPackageIsRejected() {
        rejects("app.smallthingz.reverb.nativeparity.reliability", false)
    }

    @Test fun ordinaryReliabilityPackageCannotUseUiOnlySelectors() {
        rejects("app.smallthingz.reverb.reliability", false)
        rejects("app.smallthingz.reverb.reliability", true)
    }

    @Test fun productionOrPackageSuffixImitationIsRejected() {
        rejects("app.smallthingz.reverb", true)
        rejects("app.smallthingz.reverb", false)
        rejects("foreign.app.nativeparity.reliability", true)
        rejects("app.smallthingz.reverb.nativeparity.reliability.extra", true)
    }

    private fun rejects(packageName: String, plainApplication: Boolean) {
        try {
            requireNativeMathProbeHost(packageName, plainApplication)
            fail("Unexpected native probe admission: $packageName plain=$plainApplication")
        } catch (_: IllegalStateException) {
            // Admission stopped before any probe body is invoked.
        }
    }
}
