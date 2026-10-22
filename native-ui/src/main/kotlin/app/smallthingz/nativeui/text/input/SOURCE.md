# Native IME input state

The native InputConnection bridge uses the seven command families it actually emits. These plain-text value, command, selection/composition and batch-ownership algorithms are adapted from AndroidX `ui-text-android` 1.11.2 under Apache-2.0. The original copyright/license headers are retained. No Compose runtime, saver, rendering state, platform service or observer implementation is included.

Upstream source artifact SHA-256: `6b19bce917b4725973ce0365b797644e14610729f0084818163e05ddb360ab59`. Selected source files: `TextFieldValue.kt`, `EditProcessor.kt`, `EditingBuffer.kt`, the seven used types from `EditCommand.kt`, and the overflow helpers from `MathUtils.kt`.

The app owns an ordinary StringBuilder for its short native text-field batches. Commands preserve the released replacement, clamping, reversed-selection, composition, UTF-16 and code-point rules. In particular, the released code-point command consumes lone surrogate units despite its stronger KDoc promise; this migration preserves the actual behavior. Native EditText owns key events, spans, keyboard focus and platform input lifetime.

Validation: 22 permanent behavioral JUnit tests plus a seeded/extreme-input fixture comparing this actual implementation with the unmodified pinned upstream binary. Tests were handed to the sole validation coordinator; consult its terminal receipts before claiming a pass. The source snapshot includes an exact extraction of the app-owned TextRange/AnnotatedString suffix so the IME fixture does not require loading the Android paragraph backend.

The fixture does not establish Android IME interaction, layout/pixel parity, hardware keyboard behavior or latency.
