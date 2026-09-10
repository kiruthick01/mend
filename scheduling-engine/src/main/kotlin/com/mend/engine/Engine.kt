package com.mend.engine

/**
 * Marker for the scheduling engine module.
 *
 * The engine is pure Kotlin by design: no Android, no UI, no I/O. Every
 * scheduling decision it makes must be reproducible from its inputs alone so
 * it can be unit tested without a device. Phase 0 contains no scheduling
 * logic — only the module boundary and its test harness.
 */
object Engine {

    /** Schema/behaviour version of the engine, bumped when its output changes. */
    const val VERSION: String = "0.1.0"
}
