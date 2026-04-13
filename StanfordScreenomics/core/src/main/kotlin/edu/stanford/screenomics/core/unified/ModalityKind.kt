package edu.stanford.screenomics.core.unified

/**
 * Enumerates independent sensor modalities. Each entry maps to a standalone Gradle module
 * (`:modules:audio`, `:modules:gps`, `:modules:motion`, `:modules:screenshot`) under package `com.app.modules.*`.
 */
enum class ModalityKind {
    AUDIO,
    SCREENSHOT,
    GPS,
    MOTION,
}
