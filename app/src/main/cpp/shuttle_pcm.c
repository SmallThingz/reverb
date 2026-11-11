#include <jni.h>
#include <float.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>

/* Kotlin Float/Double expressions require these exact evaluation formats. */
_Static_assert(FLT_RADIX == 2 && FLT_MANT_DIG == 24 && DBL_MANT_DIG == 53,
               "Shuttle FIR requires IEEE binary32/binary64");
_Static_assert(sizeof(float) == 4 && sizeof(double) == 8 && FLT_EVAL_METHOD == 0,
               "Shuttle FIR must not use excess intermediate precision");

#define SHUTTLE_MAX_SOURCE_FRAMES 6913
#define SHUTTLE_MAX_CHANNELS 2
#define SHUTTLE_MAX_RADIUS 214
#define SHUTTLE_TABLE_COUNT 32769
#define SHUTTLE_TILE_FRAMES 128

typedef struct {
    uint8_t pcm[SHUTTLE_MAX_SOURCE_FRAMES * SHUTTLE_MAX_CHANNELS * 2];
    float table[SHUTTLE_TABLE_COUNT];
    double weights[SHUTTLE_MAX_RADIUS * 2 + 1];
    float output[SHUTTLE_MAX_SOURCE_FRAMES * SHUTTLE_MAX_CHANNELS];
} ShuttleScratch;

static void throw_named(JNIEnv *env, const char *name, const char *message) {
    jclass type = (*env)->FindClass(env, name);
    if (type != NULL) (*env)->ThrowNew(env, type, message);
}

static int admitted(JNIEnv *env, jobject checkpoint, jmethodID run) {
    (*env)->CallVoidMethod(env, checkpoint, run);
    return !(*env)->ExceptionCheck(env);
}

static float read_pcm(const ShuttleScratch *scratch, int frame, int channel,
                      int source_frames, int channels, jboolean reverse) {
    int oriented = reverse ? source_frames - 1 - frame : frame;
    int index = (oriented * channels + channel) * 2;
    unsigned int bits = scratch->pcm[index] | ((unsigned int)scratch->pcm[index + 1] << 8);
    int value = bits >= 32768 ? (int)bits - 65536 : (int)bits;
    return (float)value;
}

static double kernel(const float *table, double distance) {
    double position = fabs(distance) * 1024.0;
    if (position >= 32768.0) return 0.0;
    int first = (int)position;
    double fraction = position - first;
    /* The Kotlin table subtraction happens in Float BEFORE promotion to Double. */
    float difference = table[first + 1] - table[first];
    return (double)table[first] + (double)difference * fraction;
}

JNIEXPORT jfloatArray JNICALL
Java_app_smallthingz_reverb_ShuttlePcmNative_filterNative(
        JNIEnv *env, jobject receiver, jbyteArray input, jint first_source_frame,
        jint source_frames, jint channels, jboolean reverse, jint output_frames,
        jint sample_frames, jdouble source_step, jfloatArray table, jobject checkpoint) {
    (void)receiver;
    if (input == NULL || table == NULL || checkpoint == NULL ||
            channels < 1 || channels > SHUTTLE_MAX_CHANNELS || first_source_frame < 0 ||
            source_frames < 1 || source_frames > SHUTTLE_MAX_SOURCE_FRAMES ||
            output_frames < 1 || output_frames > source_frames ||
            sample_frames < 1 || sample_frames > output_frames ||
            !isfinite(source_step) || source_step < 1.0 || source_step > 6.0 ||
            (output_frames > 1 && source_step != (double)(source_frames - 1) / (output_frames - 1))) {
        throw_named(env, "java/lang/IllegalArgumentException", "Invalid shuttle FIR geometry");
        return NULL;
    }
    int64_t first_byte = (int64_t)first_source_frame * channels * 2;
    int byte_count = source_frames * channels * 2;
    if (first_byte + byte_count > (*env)->GetArrayLength(env, input) ||
            (*env)->GetArrayLength(env, table) != SHUTTLE_TABLE_COUNT) {
        throw_named(env, "java/lang/IllegalArgumentException", "Invalid shuttle FIR input bounds");
        return NULL;
    }
    jclass checkpoint_class = (*env)->GetObjectClass(env, checkpoint);
    if (checkpoint_class == NULL) return NULL;
    jmethodID run = (*env)->GetMethodID(env, checkpoint_class, "run", "()V");
    if (run == NULL || !admitted(env, checkpoint, run)) return NULL;

    /* No primitive array stays pinned across a callback. This call is the sole native owner. */
    ShuttleScratch *scratch = malloc(sizeof(*scratch));
    if (scratch == NULL) {
        throw_named(env, "java/lang/OutOfMemoryError", "Cannot allocate bounded shuttle FIR scratch");
        return NULL;
    }
    jfloatArray result = NULL;
    (*env)->GetByteArrayRegion(env, input, (jsize)first_byte, byte_count, (jbyte *)scratch->pcm);
    if ((*env)->ExceptionCheck(env)) goto done;
    (*env)->GetFloatArrayRegion(env, table, 0, SHUTTLE_TABLE_COUNT, scratch->table);
    if ((*env)->ExceptionCheck(env)) goto done;

    int radius = source_step > 1.0 ? (int)ceil(32.0 * source_step / 0.9) : 0;
    double cutoff = radius > 0 ? 0.9 / source_step : 1.0;
    double previous_fraction = NAN;
    double weight_sum = 1.0;
    for (int frame = 0; frame < sample_frames; ++frame) {
        if (frame % SHUTTLE_TILE_FRAMES == 0 && !admitted(env, checkpoint, run)) goto done;
        /* Prefix length never changes full-grain output geometry or source endpoint support. */
        double position = output_frames == 1 ? (double)(source_frames / 2) :
                (double)frame * (double)(source_frames - 1) / (double)(output_frames - 1);
        int base = (int)floor(position);
        if (base < 0) base = 0;
        if (base >= source_frames) base = source_frames - 1;
        double fraction = position - base;
        if (fraction < 0.0) fraction = 0.0;
        if (fraction > 1.0) fraction = 1.0;
        if (radius == 0) {
            int second = base + 1 < source_frames ? base + 1 : source_frames - 1;
            for (int channel = 0; channel < channels; ++channel) {
                float first = read_pcm(scratch, base, channel, source_frames, channels, reverse);
                float next = read_pcm(scratch, second, channel, source_frames, channels, reverse);
                scratch->output[frame * channels + channel] =
                        first + (next - first) * (float)fraction;
            }
            continue;
        }
        int count = radius * 2 + 1;
        if (fraction != previous_fraction) {
            weight_sum = 0.0;
            for (int tap = 0; tap < count; ++tap) {
                double value = kernel(scratch->table, (tap - radius - fraction) * cutoff);
                scratch->weights[tap] = value;
                weight_sum += value;
            }
            previous_fraction = fraction;
        }
        double sums[SHUTTLE_MAX_CHANNELS] = {0.0, 0.0};
        for (int tap = 0; tap < count; ++tap) {
            int source = base + tap - radius;
            if (source < 0) source = 0;
            if (source >= source_frames) source = source_frames - 1;
            double weight = scratch->weights[tap];
            for (int channel = 0; channel < channels; ++channel) {
                sums[channel] += read_pcm(scratch, source, channel, source_frames, channels, reverse) * weight;
            }
        }
        for (int channel = 0; channel < channels; ++channel) {
            scratch->output[frame * channels + channel] = (float)(sums[channel] / weight_sum);
        }
    }
    if (!admitted(env, checkpoint, run)) goto done;
    result = (*env)->NewFloatArray(env, sample_frames * channels);
    if (result == NULL) goto done;
    (*env)->SetFloatArrayRegion(env, result, 0, sample_frames * channels, scratch->output);
    if ((*env)->ExceptionCheck(env)) result = NULL;
done:
    free(scratch);
    return result;
}
