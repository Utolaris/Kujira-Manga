package com.par9uet.jm.data.models

/** Persisted material choice; unknown/legacy values keep the existing Gaussian appearance. */
enum class GlassEffect(val id: String) {
    Gaussian("gaussian"),
    Soft("soft");

    companion object {
        fun fromId(id: String?): GlassEffect = entries.firstOrNull { it.id == id } ?: Gaussian
    }
}

data class GlassAppearance(
    val effect: GlassEffect = GlassEffect.Gaussian,
    val blurLevel: Int = 50,
    val softLevel: Int = DefaultSoftGlassLevel,
)

const val MinSoftGlassLevel = 16
const val DefaultSoftGlassLevel = 84
const val MaxSoftGlassLevel = 168
