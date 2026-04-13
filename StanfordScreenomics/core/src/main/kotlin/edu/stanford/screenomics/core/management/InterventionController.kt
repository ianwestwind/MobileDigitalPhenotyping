package edu.stanford.screenomics.core.management

/**
 * Layer 2 — Data Management: evaluates fused + cached state and issues non-destructive control directives.
 */
interface InterventionController {
    suspend fun evaluate(context: InterventionContext): InterventionDirective
}
