#include <jni.h>
#include <errno.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifndef PRODUCTION_SOURCE
#error "Pass the absolute production shuttle_pcm.c path in PRODUCTION_SOURCE"
#endif

/* Count production frees without changing the host runner's linker flags. */
static void track_native_free(void *pointer);
#define free track_native_free
#include PRODUCTION_SOURCE
#undef free

typedef enum { ARRAY_BYTES, ARRAY_FLOATS, ARRAY_RESULT } ArrayKind;
typedef struct {
    ArrayKind kind;
    jsize length;
    const jbyte *bytes;
    const jfloat *floats;
    jfloat *result;
} FakeArray;

typedef enum { FAIL_NONE, FAIL_INPUT_COPY, FAIL_TABLE_COPY, FAIL_RESULT_ALLOC,
               FAIL_RESULT_WRITE, FAIL_FIND_CLASS, FAIL_GET_OBJECT_CLASS,
               FAIL_GET_METHOD_ID } FailurePoint;
typedef struct {
    int pending;
    const char *exception;
    int find_class_calls, throw_calls, class_calls, method_calls, callback_calls;
    int byte_copies, float_copies, result_allocations, result_writes;
    int native_mallocs, native_frees;
    int callbacks_at_malloc, callbacks_at_table_copy;
    jsize result_write_start, result_write_length;
    int fail_callback_at;
    FailurePoint failure;
    FakeArray *last_result;
} Mock;

static Mock mock;
static int fail_scratch_malloc;
static size_t expected_scratch_size;
static int scratch_malloc_failures, unexpected_mallocs_while_armed;

static void track_native_free(void *pointer) {
    if (pointer != NULL) mock.native_frees++;
    free(pointer);
}

void *__real_malloc(size_t size);
void *__wrap_malloc(size_t size) {
    mock.native_mallocs++;
    mock.callbacks_at_malloc = mock.callback_calls;
    if (fail_scratch_malloc) {
        fail_scratch_malloc = 0;
        if (size == expected_scratch_size) {
            scratch_malloc_failures++;
            return NULL;
        }
        unexpected_mallocs_while_armed++;
    }
    return __real_malloc(size);
}

static void reset_mock(void) {
    if (mock.last_result != NULL) {
        free(mock.last_result->result);
        free(mock.last_result);
    }
    memset(&mock, 0, sizeof(mock));
}

static jclass JNICALL fake_find_class(JNIEnv *env, const char *name) {
    (void)env;
    mock.find_class_calls++;
    if (mock.failure == FAIL_FIND_CLASS) {
        mock.pending = 1;
        mock.exception = "FindClassFailure";
        return NULL;
    }
    if (strcmp(name, "java/lang/IllegalArgumentException") == 0) return (jclass)(uintptr_t)1;
    if (strcmp(name, "java/lang/OutOfMemoryError") == 0) return (jclass)(uintptr_t)2;
    return NULL;
}

static jint JNICALL fake_throw_new(JNIEnv *env, jclass type, const char *message) {
    (void)env; (void)message;
    mock.throw_calls++;
    mock.exception = type == (jclass)(uintptr_t)1 ? "IllegalArgumentException" : "OutOfMemoryError";
    mock.pending = 1;
    return 0;
}

static jboolean JNICALL fake_exception_check(JNIEnv *env) {
    (void)env;
    return mock.pending ? JNI_TRUE : JNI_FALSE;
}

static jclass JNICALL fake_get_object_class(JNIEnv *env, jobject object) {
    (void)env; (void)object;
    mock.class_calls++;
    if (mock.failure == FAIL_GET_OBJECT_CLASS) {
        mock.pending = 1;
        mock.exception = "GetObjectClassFailure";
        return NULL;
    }
    return (jclass)(uintptr_t)3;
}

static jmethodID JNICALL fake_get_method_id(JNIEnv *env, jclass type, const char *name,
                                            const char *signature) {
    (void)env; (void)type;
    mock.method_calls++;
    if (strcmp(name, "run") != 0 || strcmp(signature, "()V") != 0) abort();
    if (mock.failure == FAIL_GET_METHOD_ID) {
        mock.pending = 1;
        mock.exception = "GetMethodIdFailure";
        return NULL;
    }
    return (jmethodID)(uintptr_t)4;
}

static void JNICALL fake_call_void_method(JNIEnv *env, jobject object, jmethodID method, ...) {
    (void)env; (void)object; (void)method;
    mock.callback_calls++;
    if (mock.fail_callback_at == mock.callback_calls) {
        mock.pending = 1;
        mock.exception = "CallbackFailure";
    }
}

static jsize JNICALL fake_get_array_length(JNIEnv *env, jarray array) {
    (void)env;
    return ((FakeArray *)array)->length;
}

static void JNICALL fake_get_byte_array_region(JNIEnv *env, jbyteArray array, jsize start,
                                                jsize length, jbyte *destination) {
    (void)env;
    mock.byte_copies++;
    if (mock.failure == FAIL_INPUT_COPY) {
        mock.pending = 1;
        mock.exception = "InputCopyFailure";
        return;
    }
    FakeArray *source = (FakeArray *)array;
    if (source->kind != ARRAY_BYTES || start < 0 || length < 0 || start > source->length - length)
        abort();
    memcpy(destination, source->bytes + start, (size_t)length);
}

static void JNICALL fake_get_float_array_region(JNIEnv *env, jfloatArray array, jsize start,
                                                 jsize length, jfloat *destination) {
    (void)env;
    mock.float_copies++;
    mock.callbacks_at_table_copy = mock.callback_calls;
    if (mock.failure == FAIL_TABLE_COPY) {
        mock.pending = 1;
        mock.exception = "TableCopyFailure";
        return;
    }
    FakeArray *source = (FakeArray *)array;
    if (source->kind != ARRAY_FLOATS || start != 0 || length != source->length)
        abort();
    memcpy(destination, source->floats + start, (size_t)length * sizeof(jfloat));
}

static jfloatArray JNICALL fake_new_float_array(JNIEnv *env, jsize length) {
    (void)env;
    mock.result_allocations++;
    if (mock.failure == FAIL_RESULT_ALLOC) {
        mock.pending = 1;
        mock.exception = "ResultAllocFailure";
        return NULL;
    }
    FakeArray *result = calloc(1, sizeof(*result));
    if (result == NULL) abort();
    result->result = calloc((size_t)length, sizeof(jfloat));
    if (result->result == NULL) abort();
    result->kind = ARRAY_RESULT;
    result->length = length;
    mock.last_result = result;
    return (jfloatArray)result;
}

static void JNICALL fake_set_float_array_region(JNIEnv *env, jfloatArray array, jsize start,
                                                 jsize length, const jfloat *source) {
    (void)env;
    mock.result_writes++;
    mock.result_write_start = start;
    mock.result_write_length = length;
    if (mock.failure == FAIL_RESULT_WRITE) {
        mock.pending = 1;
        mock.exception = "ResultWriteFailure";
        return;
    }
    FakeArray *result = (FakeArray *)array;
    if (result->kind != ARRAY_RESULT || start < 0 || length < 0 || start > result->length - length)
        abort();
    memcpy(result->result + start, source, (size_t)length * sizeof(jfloat));
}

static const struct JNINativeInterface_ jni_functions = {
    .FindClass = fake_find_class,
    .ThrowNew = fake_throw_new,
    .ExceptionCheck = fake_exception_check,
    .GetObjectClass = fake_get_object_class,
    .GetMethodID = fake_get_method_id,
    .CallVoidMethod = fake_call_void_method,
    .GetArrayLength = fake_get_array_length,
    .GetByteArrayRegion = fake_get_byte_array_region,
    .GetFloatArrayRegion = fake_get_float_array_region,
    .NewFloatArray = fake_new_float_array,
    .SetFloatArrayRegion = fake_set_float_array_region,
};

static int failures, assertions;
static void expect(int pass, const char *name) {
    assertions++;
    if (!pass) failures++;
    printf("%s %s\n", pass ? "PASS" : "FAIL", name);
}

static FakeArray byte_array(const jbyte *bytes, jsize length) {
    FakeArray array = { .kind = ARRAY_BYTES, .length = length, .bytes = bytes };
    return array;
}

static FakeArray float_array(const jfloat *floats, jsize length) {
    FakeArray array = { .kind = ARRAY_FLOATS, .length = length, .floats = floats };
    return array;
}

static int has_exception(const char *name) {
    return mock.pending && mock.exception != NULL && strcmp(mock.exception, name) == 0;
}

static jlong create_scratch(FakeArray *table, jobject checkpoint) {
    JNIEnv env = &jni_functions;
    return Java_app_smallthingz_reverb_ShuttlePcmNative_createScratchNative(
        &env, NULL, (jfloatArray)table, checkpoint);
}

static void release_scratch(jlong handle) {
    JNIEnv env = &jni_functions;
    Java_app_smallthingz_reverb_ShuttlePcmNative_releaseScratchNative(&env, NULL, handle);
}

static jfloatArray invoke(FakeArray *input, jint first, jint source_frames, jint channels,
                          jboolean reverse, jint output_frames, jint sample_frames,
                          jdouble source_step, jlong handle, jobject checkpoint) {
    JNIEnv env = &jni_functions;
    jfloatArray result = Java_app_smallthingz_reverb_ShuttlePcmNative_filterWithScratchNative(
        &env, NULL, handle, (jbyteArray)input, first, source_frames, channels, reverse,
        output_frames, sample_frames, source_step, checkpoint);
    expect(mock.native_mallocs == 0 && mock.native_frees == 0 && mock.float_copies == 0,
           "filter_does_not_allocate_free_or_copy_scratch");
    if (result != NULL) {
        expect(mock.byte_copies == 1, "successful_filter_copies_pcm_once");
        jsize length = sample_frames * channels;
        expect(!mock.pending && mock.result_allocations == 1 && mock.result_writes == 1 &&
               ((FakeArray *)result)->length == length && mock.result_write_start == 0 &&
               mock.result_write_length == length, "filter_allocates_and_writes_exact_sample_prefix");
    }
    return result;
}

static void put_pcm16(jbyte *bytes, int frame, int channel, int channels, int value) {
    unsigned int bits = (unsigned int)(uint16_t)(int16_t)value;
    int offset = (frame * channels + channel) * 2;
    bytes[offset] = (jbyte)(bits & 0xffU);
    bytes[offset + 1] = (jbyte)((bits >> 8) & 0xffU);
}

static int near(float actual, float expected, float tolerance) {
    return isfinite(actual) && fabsf(actual - expected) <= tolerance;
}

static void reset_with_failure(FailurePoint failure) {
    reset_mock();
    mock.failure = failure;
}

static void expect_rejected(const char *name, FakeArray *input, jint first, jint frames,
                           jint channels, jint output, jint samples, jdouble step,
                           jlong handle, jobject checkpoint) {
    reset_mock();
    jfloatArray result = invoke(input, first, frames, channels, JNI_FALSE, output, samples,
                                step, handle, checkpoint);
    expect(result == NULL && has_exception("IllegalArgumentException") &&
           mock.callback_calls == 0 && mock.byte_copies == 0 && mock.float_copies == 0 &&
           mock.result_allocations == 0, name);
}

static void expect_create_rejected(const char *name, FakeArray *table, jobject checkpoint) {
    reset_mock();
    jlong handle = create_scratch(table, checkpoint);
    expect(handle == 0 && has_exception("IllegalArgumentException") &&
           mock.callback_calls == 0 && mock.native_mallocs == 0 && mock.float_copies == 0, name);
    if (handle != 0) release_scratch(handle);
}

static jlong expect_new_owner(FakeArray *table, jobject checkpoint, const char *name) {
    reset_mock();
    jlong handle = create_scratch(table, checkpoint);
    expect(handle != 0 && !mock.pending && mock.callback_calls == 1 &&
           mock.native_mallocs == 1 && mock.native_frees == 0 && mock.float_copies == 1 &&
           mock.callbacks_at_malloc == 1 && mock.callbacks_at_table_copy == 1 &&
           mock.byte_copies == 0 && mock.result_allocations == 0, name);
    return handle;
}

int main(void) {
    enum { TABLE_COUNT = 32769 };
    jfloat *table_values = malloc(TABLE_COUNT * sizeof(*table_values));
    if (table_values == NULL) return 2;
    for (int i = 0; i < TABLE_COUNT; ++i) {
        float distance = (float)i / 1024.0f;
        table_values[i] = 1.0f / (1.0f + distance * distance);
    }
    FakeArray table = float_array(table_values, TABLE_COUNT);
    int checkpoint_token = 0;
    jobject checkpoint = (jobject)&checkpoint_token;

    jlong handle = expect_new_owner(&table, checkpoint, "owner_allocates_and_copies_table_once");
    if (handle == 0) {
        free(table_values);
        return 1;
    }

    /* Exact PCM16 byte order, signed endpoints, channels, reverse, and nonzero seek offset. */
    jbyte pcm[7 * 2 * 2];
    const int values[7][2] = {
        {111, -111}, {222, -222}, {-32768, 32767}, {-1234, 4321},
        {7777, -8888}, {999, -999}, {123, -123}
    };
    for (int frame = 0; frame < 7; ++frame)
        for (int channel = 0; channel < 2; ++channel)
            put_pcm16(pcm, frame, channel, 2, values[frame][channel]);
    FakeArray input = byte_array(pcm, sizeof(pcm));
    reset_mock();
    jfloatArray output = invoke(&input, 2, 3, 2, JNI_FALSE, 3, 3, 1.0, handle, checkpoint);
    FakeArray *samples = (FakeArray *)output;
    int exact = samples != NULL && samples->length == 6;
    for (int frame = 0; exact && frame < 3; ++frame)
        for (int channel = 0; channel < 2; ++channel)
            exact &= samples->result[frame * 2 + channel] == (float)values[frame + 2][channel];
    expect(exact, "pcm16_little_endian_stereo_window_step1_exact");

    reset_mock();
    output = invoke(&input, 2, 3, 2, JNI_TRUE, 3, 3, 1.0, handle, checkpoint);
    samples = (FakeArray *)output;
    exact = samples != NULL && samples->length == 6;
    for (int frame = 0; exact && frame < 3; ++frame)
        for (int channel = 0; channel < 2; ++channel)
            exact &= samples->result[frame * 2 + channel] == (float)values[4 - frame][channel];
    expect(exact, "reverse_is_exact_frame_reversal_with_channels_intact");

    /* Single output position is the integer center; even length uses n/2. */
    reset_mock();
    output = invoke(&input, 1, 5, 2, JNI_FALSE, 1, 1, 1.0, handle, checkpoint);
    samples = (FakeArray *)output;
    expect(samples != NULL && samples->length == 2 &&
           samples->result[0] == (float)values[3][0] && samples->result[1] == (float)values[3][1],
           "single_output_uses_odd_source_integer_center");
    reset_mock();
    output = invoke(&input, 1, 4, 2, JNI_FALSE, 1, 1, 1.0, handle, checkpoint);
    samples = (FakeArray *)output;
    expect(samples != NULL && samples->length == 2 &&
           samples->result[0] == (float)values[3][0] && samples->result[1] == (float)values[3][1],
           "single_output_even_source_uses_upper_center_index");
    jbyte mono_one_pcm[2];
    put_pcm16(mono_one_pcm, 0, 0, 1, -321);
    FakeArray mono_one_input = byte_array(mono_one_pcm, sizeof(mono_one_pcm));
    reset_mock();
    output = invoke(&mono_one_input, 0, 1, 1, JNI_FALSE, 1, 1, 1.0, handle, checkpoint);
    samples = (FakeArray *)output;
    expect(samples != NULL && samples->length == 1 && samples->result[0] == -321.0f,
           "single_frame_mono_minimum_geometry_succeeds_exactly");

    /* Normalized FIR preserves DC at clamped endpoints, for both channels and directions. */
    jbyte constant_pcm[6 * 2 * 2];
    for (int frame = 0; frame < 6; ++frame) {
        put_pcm16(constant_pcm, frame, 0, 2, -23456);
        put_pcm16(constant_pcm, frame, 1, 2, 30001);
    }
    FakeArray constant_input = byte_array(constant_pcm, sizeof(constant_pcm));
    reset_mock();
    output = invoke(&constant_input, 0, 6, 2, JNI_FALSE, 4, 4, 5.0 / 3.0, handle, checkpoint);
    samples = (FakeArray *)output;
    int constant_ok = samples != NULL && samples->length == 8;
    for (int i = 0; constant_ok && i < samples->length; i += 2)
        constant_ok &= near(samples->result[i], -23456.0f, 0.02f) &&
                       near(samples->result[i + 1], 30001.0f, 0.02f);
    expect(constant_ok, "normalized_fir_preserves_stereo_dc_through_edges");
    reset_mock();
    output = invoke(&constant_input, 0, 6, 2, JNI_TRUE, 4, 4, 5.0 / 3.0, handle, checkpoint);
    samples = (FakeArray *)output;
    constant_ok = samples != NULL && samples->length == 8;
    for (int i = 0; constant_ok && i < samples->length; i += 2)
        constant_ok &= near(samples->result[i], -23456.0f, 0.02f) &&
                       near(samples->result[i + 1], 30001.0f, 0.02f);
    expect(constant_ok, "reverse_normalized_fir_preserves_each_channel_dc");

    reset_mock();
    jfloatArray full = invoke(&input, 0, 6, 2, JNI_TRUE, 4, 4, 5.0 / 3.0,
                              handle, checkpoint);
    FakeArray *full_samples = (FakeArray *)full;
    jfloat full_copy[8];
    int full_ok = full_samples != NULL && full_samples->length == 8;
    if (full_ok) memcpy(full_copy, full_samples->result, sizeof(full_copy));
    reset_mock();
    jfloatArray prefix = invoke(&input, 0, 6, 2, JNI_TRUE, 4, 2, 5.0 / 3.0,
                                handle, checkpoint);
    FakeArray *prefix_samples = (FakeArray *)prefix;
    int prefix_ok = full_ok && prefix_samples != NULL && prefix_samples->length == 4;
    for (int i = 0; prefix_ok && i < 4; ++i)
        prefix_ok &= prefix_samples->result[i] == full_copy[i];
    expect(prefix_ok, "short_sample_count_is_exact_prefix_of_full_geometry");

    /* Maximum legal source/output geometry with step six is bounded and finite. */
    const int max_frames = 6913;
    const int max_output = 1153;
    jbyte *max_pcm = malloc((size_t)max_frames * 2U * 2U);
    if (max_pcm == NULL) {
        reset_mock();
        release_scratch(handle);
        free(table_values);
        return 2;
    }
    for (int frame = 0; frame < max_frames; ++frame) {
        put_pcm16(max_pcm, frame, 0, 2, 17);
        put_pcm16(max_pcm, frame, 1, 2, -22);
    }
    FakeArray max_input = byte_array(max_pcm, max_frames * 2 * 2);
    reset_mock();
    output = invoke(&max_input, 0, max_frames, 2, JNI_FALSE, max_output, max_output,
                    6.0, handle, checkpoint);
    samples = (FakeArray *)output;
    int max_ok = samples != NULL && samples->length == max_output * 2;
    for (int i = 0; max_ok && i < samples->length; i += 2)
        max_ok &= near(samples->result[i], 17.0f, 0.02f) && near(samples->result[i + 1], -22.0f, 0.02f);
    expect(max_ok, "maximum_source_step6_full_output_is_bounded_and_constant");
    reset_mock();
    output = invoke(&max_input, 0, max_frames, 2, JNI_FALSE, max_frames, max_frames,
                    1.0, handle, checkpoint);
    samples = (FakeArray *)output;
    max_ok = samples != NULL && samples->length == max_frames * 2;
    for (int i = 0; max_ok && i < samples->length; i += 2)
        max_ok &= samples->result[i] == 17.0f && samples->result[i + 1] == -22.0f;
    expect(max_ok, "maximum_source_and_output_count_step1_are_bounded");

    /* Representatives from each invalid geometry/bounds partition reject before work. */
    expect_rejected("rejects_null_input_before_work", NULL, 0, 1, 1, 1, 1, 1.0, handle, checkpoint);
    expect_rejected("rejects_zero_handle_before_work", &input, 0, 1, 1, 1, 1, 1.0, 0, checkpoint);
    expect_create_rejected("create_rejects_null_table_before_work", NULL, checkpoint);
    expect_create_rejected("create_rejects_null_checkpoint_before_work", &table, NULL);
    expect_rejected("rejects_null_checkpoint_before_work", &input, 0, 1, 1, 1, 1, 1.0, handle, NULL);
    expect_rejected("rejects_channels_below_range", &input, 0, 1, 0, 1, 1, 1.0, handle, checkpoint);
    expect_rejected("rejects_channels_above_range", &input, 0, 1, 3, 1, 1, 1.0, handle, checkpoint);
    expect_rejected("rejects_negative_seek_frame", &input, -1, 1, 1, 1, 1, 1.0, handle, checkpoint);
    expect_rejected("rejects_empty_source", &input, 0, 0, 1, 1, 1, 1.0, handle, checkpoint);
    expect_rejected("rejects_source_above_bound", &input, 0, 6914, 1, 1, 1, 1.0, handle, checkpoint);
    expect_rejected("rejects_empty_output", &input, 0, 1, 1, 0, 1, 1.0, handle, checkpoint);
    expect_rejected("rejects_output_larger_than_source", &input, 0, 1, 1, 2, 1, 1.0, handle, checkpoint);
    expect_rejected("rejects_empty_sample_prefix", &input, 0, 1, 1, 1, 0, 1.0, handle, checkpoint);
    expect_rejected("rejects_sample_prefix_larger_than_output", &input, 0, 2, 1, 2, 3, 1.0, handle, checkpoint);
    expect_rejected("rejects_nan_step", &input, 0, 2, 1, 2, 1, NAN, handle, checkpoint);
    expect_rejected("rejects_infinite_step", &input, 0, 2, 1, 2, 1, INFINITY, handle, checkpoint);
    expect_rejected("rejects_step_below_minimum", &input, 0, 2, 1, 2, 1, 0.5, handle, checkpoint);
    expect_rejected("rejects_step_above_maximum", &input, 0, 2, 1, 2, 1, 6.1, handle, checkpoint);
    expect_rejected("rejects_inconsistent_step_geometry", &input, 0, 6, 1, 4, 1, 1.5, handle, checkpoint);
    expect_rejected("rejects_offset_plus_window_past_input", &input, 10, 5, 1, 5, 1, 1.0, handle, checkpoint);
    expect_rejected("rejects_intmax_seek_without_offset_wrap", &input, INT32_MAX, 1, 2, 1, 1, 1.0, handle, checkpoint);
    FakeArray short_table = float_array(table_values, TABLE_COUNT - 1);
    expect_create_rejected("create_rejects_noncanonical_kernel_table_length", &short_table, checkpoint);

    /* JNI failures propagate while the scratch owner stays live. */
    reset_with_failure(FAIL_GET_OBJECT_CLASS);
    output = invoke(&input, 0, 1, 1, JNI_FALSE, 1, 1, 1.0, handle, checkpoint);
    expect(output == NULL && has_exception("GetObjectClassFailure") && mock.result_allocations == 0,
           "get_object_class_failure_returns_before_render_result");
    reset_with_failure(FAIL_GET_METHOD_ID);
    output = invoke(&input, 0, 1, 1, JNI_FALSE, 1, 1, 1.0, handle, checkpoint);
    expect(output == NULL && has_exception("GetMethodIdFailure") && mock.result_allocations == 0,
           "get_method_id_failure_returns_before_render_result");
    reset_with_failure(FAIL_INPUT_COPY);
    output = invoke(&input, 0, 1, 1, JNI_FALSE, 1, 1, 1.0, handle, checkpoint);
    expect(output == NULL && has_exception("InputCopyFailure") && mock.byte_copies == 1 &&
           mock.result_allocations == 0, "input_region_exception_preserves_owner_without_result");
    reset_with_failure(FAIL_RESULT_ALLOC);
    output = invoke(&input, 0, 1, 1, JNI_FALSE, 1, 1, 1.0, handle, checkpoint);
    expect(output == NULL && has_exception("ResultAllocFailure") && mock.result_allocations == 1,
           "result_allocation_failure_returns_null_after_bounded_processing");
    reset_with_failure(FAIL_RESULT_WRITE);
    output = invoke(&input, 0, 1, 1, JNI_FALSE, 1, 1, 1.0, handle, checkpoint);
    expect(output == NULL && has_exception("ResultWriteFailure") && mock.result_allocations == 1 && mock.result_writes == 1,
           "result_region_exception_never_publishes_result");

    /* A failing FindClass already has a pending JNI exception; ThrowNew must not run. */
    reset_with_failure(FAIL_FIND_CLASS);
    output = invoke(&input, 0, 1, 0, JNI_FALSE, 1, 1, 1.0, handle, checkpoint);
    expect(output == NULL && has_exception("FindClassFailure") &&
           mock.find_class_calls == 1 && mock.throw_calls == 0 &&
           mock.callback_calls == 0 && mock.byte_copies == 0 && mock.result_allocations == 0,
           "find_class_failure_preserves_existing_exception_without_more_work");

    /* Failed creation must never publish an owner. */
    reset_with_failure(FAIL_TABLE_COPY);
    jlong failed_handle = create_scratch(&table, checkpoint);
    expect(failed_handle == 0 && has_exception("TableCopyFailure") &&
           mock.callback_calls == 1 && mock.native_mallocs == 1 && mock.native_frees == 1 &&
           mock.float_copies == 1 && mock.byte_copies == 0 && mock.result_allocations == 0,
           "table_region_exception_frees_unpublished_scratch");

    reset_mock();
    mock.fail_callback_at = 1;
    failed_handle = create_scratch(&table, checkpoint);
    expect(failed_handle == 0 && has_exception("CallbackFailure") && mock.callback_calls == 1 &&
           mock.native_mallocs == 0 && mock.native_frees == 0 && mock.float_copies == 0,
           "create_cancellation_precedes_allocation_and_table_copy");

    reset_mock();
    expected_scratch_size = sizeof(ShuttleScratch);
    fail_scratch_malloc = 1;
    failed_handle = create_scratch(&table, checkpoint);
    fail_scratch_malloc = 0;
    expect(failed_handle == 0 && has_exception("OutOfMemoryError") &&
           mock.find_class_calls == 1 && mock.throw_calls == 1 && mock.callback_calls == 1 &&
           mock.callbacks_at_malloc == 1 && mock.native_mallocs == 1 && mock.native_frees == 0 &&
           mock.byte_copies == 0 && mock.float_copies == 0 && mock.result_allocations == 0 &&
           scratch_malloc_failures == 1 && unexpected_mallocs_while_armed == 0,
           "scratch_malloc_failure_throws_oom_after_create_checkpoint");

    /* Initial, frame-zero, each 128-frame boundary and final checkpoint remain live. */
    jbyte callback_pcm[257 * 2];
    for (int frame = 0; frame < 257; ++frame)
        put_pcm16(callback_pcm, frame, 0, 1, frame - 128);
    FakeArray callback_input = byte_array(callback_pcm, sizeof(callback_pcm));
    const jint callback_sizes[] = { 1, 128, 129, 257 };
    const int callback_counts[] = { 3, 3, 4, 5 };
    for (int i = 0; i < 4; ++i) {
        char name[96];
        reset_mock();
        output = invoke(&callback_input, 0, 257, 1, JNI_FALSE, 257, callback_sizes[i],
                        1.0, handle, checkpoint);
        snprintf(name, sizeof(name), "sample_prefix_%d_has_expected_checkpoint_count", callback_sizes[i]);
        expect(output != NULL && mock.callback_calls == callback_counts[i], name);
    }
    reset_mock();
    mock.fail_callback_at = 1;
    output = invoke(&callback_input, 0, 257, 1, JNI_FALSE, 257, 257, 1.0, handle, checkpoint);
    expect(output == NULL && has_exception("CallbackFailure") && mock.callback_calls == 1 &&
           mock.byte_copies == 0 && mock.result_allocations == 0,
           "initial_callback_cancellation_prevents_pcm_copy_and_result_allocation");
    reset_mock();
    mock.fail_callback_at = 3;
    output = invoke(&callback_input, 0, 257, 1, JNI_FALSE, 257, 257, 1.0, handle, checkpoint);
    expect(output == NULL && has_exception("CallbackFailure") && mock.callback_calls == 3 &&
           mock.result_allocations == 0,
           "tile_callback_cancellation_prevents_result_publication");
    reset_mock();
    mock.fail_callback_at = 5;
    output = invoke(&callback_input, 0, 257, 1, JNI_FALSE, 257, 257, 1.0, handle, checkpoint);
    expect(output == NULL && has_exception("CallbackFailure") && mock.callback_calls == 5 &&
           mock.result_allocations == 0,
           "terminal_callback_cancellation_prevents_result_publication");

    reset_mock();
    output = invoke(&callback_input, 0, 257, 1, JNI_TRUE, 257, 2, 1.0, handle, checkpoint);
    samples = (FakeArray *)output;
    expect(samples != NULL && samples->length == 2 && samples->result[0] == 128.0f &&
           samples->result[1] == 127.0f && mock.callback_calls == 3,
           "same_owner_renders_short_reverse_prefix_after_cancellation");

    reset_mock();
    release_scratch(handle);
    expect(!mock.pending && mock.native_frees == 1 && mock.native_mallocs == 0 &&
           mock.float_copies == 0 && mock.callback_calls == 0, "release_frees_owner_once");

    handle = expect_new_owner(&table, checkpoint, "fresh_owner_allocates_and_copies_after_release");
    if (handle != 0) {
        reset_mock();
        output = invoke(&input, 0, 6, 2, JNI_TRUE, 4, 2, 5.0 / 3.0, handle, checkpoint);
        samples = (FakeArray *)output;
        int fresh_ok = full_ok && samples != NULL && samples->length == 4;
        for (int i = 0; fresh_ok && i < 4; ++i)
            fresh_ok &= samples->result[i] == full_copy[i];
        expect(fresh_ok, "fresh_owner_renders_expected_fir_prefix");

        reset_mock();
        release_scratch(handle);
        expect(!mock.pending && mock.native_frees == 1 && mock.native_mallocs == 0,
               "fresh_owner_releases_its_scratch_once");
    }

    reset_mock();
    free(max_pcm);
    free(table_values);
    printf("assertions=%d failures=%d\n", assertions, failures);
    return failures == 0 ? 0 : 1;
}
