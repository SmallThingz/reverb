package app.smallthingz.reverb

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.TypedValue
import app.smallthingz.reverb.nativeui.NOISE_TILE_SIZE
import app.smallthingz.reverb.nativeui.ReverbNoiseDrawable
import app.smallthingz.reverb.nativeui.ReverbNoiseTexture
import app.smallthingz.reverb.nativeui.generateNoisePixels
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.IdentityHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Supplemental QA only: give the unchanged released renderer the native grain input.
 * Neither the original PNG nor either renderer is edited. Callers override only LocalResources,
 * before the reference's first composition, and retain their ordinary full-frame comparisons.
 */
internal class NativeParityCommonGrainSession {
    private val generated = generateNoisePixels()
    private val generatedHash = hashPixels(generated)
    private val suppliedPng: ByteArray
    private val decodedHash: String
    private val wrappers = IdentityHashMap<Resources, Resources>()
    private val loads = JSONArray()
    private var nativeHash: String? = null
    private var referenceVerified = false

    init {
        // This warms only this isolated supplemental fixture. Production cold startup is unchanged.
        ReverbNoiseTexture.prepare()
        val encoded = Bitmap.createBitmap(generated, NOISE_TILE_SIZE, NOISE_TILE_SIZE,
            Bitmap.Config.ARGB_8888).apply { density = Bitmap.DENSITY_NONE }
        try {
            suppliedPng = ByteArrayOutputStream().use { stream ->
                check(encoded.compress(Bitmap.CompressFormat.PNG, 100, stream))
                stream.toByteArray()
            }
        } finally { encoded.recycle() }
        val decoded = checkNotNull(BitmapFactory.decodeByteArray(suppliedPng, 0, suppliedPng.size))
        try {
            check(decoded.width == NOISE_TILE_SIZE && decoded.height == NOISE_TILE_SIZE)
            val pixels = IntArray(generated.size)
            decoded.getPixels(pixels, 0, NOISE_TILE_SIZE, 0, 0, NOISE_TILE_SIZE, NOISE_TILE_SIZE)
            check(pixels.contentEquals(generated)) { "Common grain PNG changed generated ARGB pixels" }
            decodedHash = hashPixels(pixels)
        } finally { decoded.recycle() }
    }

    @Synchronized
    fun referenceResources(base: Resources): Resources = wrappers.getOrPut(base) {
        @Suppress("DEPRECATION")
        object : Resources(base.assets, base.displayMetrics, base.configuration) {
            override fun openRawResource(id: Int): InputStream = openRawResource(id, TypedValue())

            override fun openRawResource(id: Int, value: TypedValue): InputStream {
                // Delegate first: preserve Android's actual nodpi TypedValue and all other assets.
                val original = base.openRawResource(id, value)
                if (id != R.drawable.settings_noise_tile) return original
                val originalHash = original.use { hashBytes(it.readBytes()) }
                check(originalHash == ORIGINAL_PNG_SHA256) { "Released grain resource changed" }
                check(value.density == TypedValue.DENSITY_NONE) { "Released grain is no longer nodpi" }
                synchronized(this@NativeParityCommonGrainSession) {
                    loads.put(JSONObject().put("resource_id", id)
                        .put("resource_name", base.getResourceName(id))
                        .put("typed_value_density", value.density)
                        .put("typed_value_asset_cookie", value.assetCookie)
                        .put("typed_value_string", value.string?.toString())
                        .put("original_png_sha256", originalHash))
                }
                return ByteArrayInputStream(suppliedPng)
            }
        }
    }

    /** A seed-only global reference cache must be cold for both background and top-bar phases. */
    @Synchronized
    fun verifyReferenceLoads() {
        check(loads.length() == 2) {
            "Expected exactly two cold reference grain loads; observed ${loads.length()}. Warmed or bypassed reference cache is not a common-grain pass."
        }
        referenceVerified = true
    }

    /** Bind supplied pixels to the real cached native tile, using its existing shader accessor. */
    @Synchronized
    fun verifyNativeReady() {
        val shader = checkNotNull(ReverbNoiseTexture.shader(ReverbNoiseDrawable.SEED_BACKGROUND)) {
            "Native grain is not ready; supplemental settled comparison is unqualified"
        }
        checkNotNull(ReverbNoiseTexture.shader(ReverbNoiseDrawable.SEED_TOP_BAR))
        if (nativeHash != null) return
        val actual = Bitmap.createBitmap(NOISE_TILE_SIZE, NOISE_TILE_SIZE, Bitmap.Config.ARGB_8888)
            .apply { density = Bitmap.DENSITY_NONE }
        try {
            // Full tile, at 1:1 into transparency, without filtering, tint, masking or tolerance.
            Canvas(actual).drawRect(0f, 0f, NOISE_TILE_SIZE.toFloat(), NOISE_TILE_SIZE.toFloat(),
                Paint().apply { this.shader = shader })
            val pixels = IntArray(generated.size)
            actual.getPixels(pixels, 0, NOISE_TILE_SIZE, 0, 0, NOISE_TILE_SIZE, NOISE_TILE_SIZE)
            check(pixels.contentEquals(generated)) { "Actual native cached grain differs from supplied input" }
            nativeHash = hashPixels(pixels)
        } finally { actual.recycle() }
    }

    @Synchronized
    fun evidence(): JSONObject = JSONObject()
        .put("scope", "Supplemental full-frame zero-tolerance comparison with only reference grain input normalized; original raw comparison remains separate")
        .put("resource_override", "LocalResources only; original Context, applicationContext, theme and window retained")
        .put("original_png_sha256", ORIGINAL_PNG_SHA256)
        .put("supplied_png_sha256", hashBytes(suppliedPng))
        .put("pixel_encoding", "ARGB32 big endian, row major")
        .put("width", NOISE_TILE_SIZE).put("height", NOISE_TILE_SIZE)
        .put("generator_argb_sha256", generatedHash)
        .put("decoded_argb_sha256", decodedHash)
        .put("actual_native_shader_argb_sha256", nativeHash ?: JSONObject.NULL)
        .put("interception_count", loads.length()).put("loads", JSONArray(loads.toString()))
        .put("reference_cache_verified", referenceVerified)
        .put("native_tile_verified", nativeHash != null)
        .put("cold_start_parity_claimed", false)

    private companion object {
        const val ORIGINAL_PNG_SHA256 = "55ce1eaa8d7c1b78eb0b8298b101aec91ca1ef1d6b467f8899380046355dc517"

        fun hashPixels(pixels: IntArray): String {
            val bytes = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES)
            bytes.asIntBuffer().put(pixels)
            return hashBytes(bytes.array())
        }

        fun hashBytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
