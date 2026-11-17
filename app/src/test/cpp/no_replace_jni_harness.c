#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

static int syscall_error, close_error, stat_error, actual_io;
static int move_calls, close_calls;
static int open_error, open_calls, nonregular;
static int alloc_calls, frees, fail_alloc_at, copy_calls, fail_copy_at, pending;
static int boundary_errors, ownership_errors, live_count;
static void *live_pointers[16];
static void *fixture_malloc(size_t size) {
    alloc_calls++;
    if (alloc_calls == fail_alloc_at) return NULL;
    void *pointer = malloc(size);
    if (pointer != NULL) {
        int slot;
        for (slot = 0; slot < 16 && live_pointers[slot] != NULL; slot++) { }
        if (slot == 16) { ownership_errors++; free(pointer); return NULL; }
        live_pointers[slot] = pointer;
        live_count++;
    }
    return pointer;
}
static void fixture_free(void *pointer) {
    if (pointer == NULL) return;
    int slot;
    for (slot = 0; slot < 16 && live_pointers[slot] != pointer; slot++) { }
    if (slot == 16) { ownership_errors++; return; }
    live_pointers[slot] = NULL;
    live_count--;
    frees++;
    free(pointer);
}
static int fixture_open(const char *path, int flags) {
    open_calls++;
    if (!actual_io && (strcmp(path, "/owned") != 0 ||
        flags != (O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW))) boundary_errors++;
    if (!actual_io && open_error) { errno = open_error; return -1; }
    return actual_io ? open(path, flags) : 42;
}
static int fixture_fstatat(int directory, const char *name, struct stat *info, int flags) {
    if (!actual_io && (directory != 42 || strcmp(name, "old.wav") != 0 ||
        flags != AT_SYMLINK_NOFOLLOW)) boundary_errors++;
    if (actual_io) return fstatat(directory, name, info, flags);
    if (stat_error) { errno = stat_error; return -1; }
    memset(info, 0, sizeof(*info)); info->st_mode = (nonregular ? S_IFLNK : S_IFREG) | 0600; return 0;
}
static long fixture_syscall(long number, int from_dir, const char *from, int to_dir,
                            const char *to, unsigned int flags) {
    move_calls++;
    if (!actual_io && (number != SYS_renameat2 || from_dir != 42 || to_dir != 42 ||
        strcmp(from, "old.wav") != 0 || strcmp(to, "new.wav") != 0 ||
        flags != RENAME_NOREPLACE)) boundary_errors++;
    if (actual_io) return syscall(number, from_dir, from, to_dir, to, flags);
    if (syscall_error) { errno = syscall_error; return -1; }
    return 0;
}
static int fixture_close(int descriptor) {
    close_calls++;
    if (!actual_io && descriptor != 42) boundary_errors++;
    if (actual_io) return close(descriptor);
    if (close_error) { errno = close_error; return -1; }
    return 0;
}
#define malloc fixture_malloc
#define free fixture_free
#define open fixture_open
#define fstatat fixture_fstatat
#define syscall fixture_syscall
#define close fixture_close
#include PRODUCTION_SOURCE
#undef malloc
#undef free
#undef open
#undef fstatat
#undef syscall
#undef close

struct bytes { const char *data; jsize size; };
static jsize JNICALL array_length(JNIEnv *env, jarray array) {
    (void)env; return ((struct bytes *)array)->size;
}
static void JNICALL array_region(JNIEnv *env, jbyteArray array, jsize start,
                                 jsize size, jbyte *destination) {
    copy_calls++;
    if (copy_calls == fail_copy_at) { pending = 1; return; }
    (void)env; memcpy(destination, ((struct bytes *)array)->data + start, (size_t)size);
}
static jboolean JNICALL no_exception(JNIEnv *env) { (void)env; return pending ? JNI_TRUE : JNI_FALSE; }
static const struct JNINativeInterface_ jni_functions = {
    .GetArrayLength = array_length,
    .GetByteArrayRegion = array_region,
    .ExceptionCheck = no_exception,
};
static int invoke(const char *parent, const char *source, const char *target) {
    JNIEnv env = &jni_functions;
    struct bytes p = {parent, (jsize)strlen(parent)};
    struct bytes s = {source, (jsize)strlen(source)};
    struct bytes t = {target, (jsize)strlen(target)};
    return Java_app_smallthingz_reverb_NoReplaceMoveNative_renameNoReplace(
        &env, NULL, (jbyteArray)&p, (jbyteArray)&s, (jbyteArray)&t);
}
static int failures, cases;
static void check_case(const char *name, int rename_error, int terminal_error,
                       int before_error, int expected, int expected_moves) {
    actual_io = 0; syscall_error = rename_error; close_error = terminal_error;
    stat_error = before_error; move_calls = 0; close_calls = 0;
    int got = invoke("/owned", "old.wav", "new.wav");
    int pass = got == expected && move_calls == expected_moves && close_calls == 1 &&
        boundary_errors == 0 && ownership_errors == 0 && live_count == 0;
    printf("%s %s status=%d expected=%d moves=%d closes=%d\n",
           pass ? "PASS" : "FAIL", name, got, expected, move_calls, close_calls);
    cases++; if (!pass) failures++;
}
static void reset_added(void) {
    actual_io = syscall_error = close_error = stat_error = 0;
    move_calls = close_calls = open_error = open_calls = nonregular = 0;
    alloc_calls = frees = fail_alloc_at = copy_calls = fail_copy_at = pending = 0;
}
static void report_added(const char *name, int pass, int got, int expected) {
    printf("%s %s status=%d expected=%d allocs=%d frees=%d opens=%d moves=%d closes=%d\n",
        pass && boundary_errors == 0 && ownership_errors == 0 && live_count == 0 ? "PASS" : "FAIL", name, got, expected,
        alloc_calls, frees, open_calls, move_calls, close_calls);
    cases++;
    if (!pass || boundary_errors != 0 || ownership_errors != 0 || live_count != 0) failures++;
}
static void added_admission_cases(void) {
    /* Every source/destination failure also proves earlier decoded buffers retire. */
    for (int slot = 0; slot < 3; slot++) for (int mode = 0; mode < 5; mode++) {
        reset_added();
        struct bytes parent = {"/owned", 6}, source = {"old.wav", 7}, target = {"new.wav", 7};
        struct bytes *args[] = {&parent, &source, &target};
        if (mode == 0) args[slot] = NULL;
        if (mode == 1) args[slot]->size = 0;
        if (mode == 2) fail_alloc_at = slot + 1;
        if (mode == 3) fail_copy_at = slot + 1;
        if (mode == 4) { args[slot]->data = "x\0y"; args[slot]->size = 3; }
        JNIEnv env = &jni_functions;
        int got = Java_app_smallthingz_reverb_NoReplaceMoveNative_renameNoReplace(
            &env, NULL, (jbyteArray)args[0], (jbyteArray)args[1], (jbyteArray)args[2]);
        int expected = mode == 2 ? ENOMEM : mode == 3 ? EFAULT : EINVAL;
        int expected_allocs = slot + (mode >= 2 ? 1 : 0);
        int expected_frees = slot + (mode >= 3 ? 1 : 0);
        int expected_copies = slot + (mode >= 3 ? 1 : 0);
        char name[80];
        snprintf(name, sizeof(name), "path-slot-%d-failure-partition-%d", slot, mode);
        report_added(name, got == expected && alloc_calls == expected_allocs &&
            frees == expected_frees && copy_calls == expected_copies &&
            pending == (mode == 3) && open_calls == 0 && move_calls == 0 && close_calls == 0,
            got, expected);
    }
    const char *invalid[] = {"a/b", ".", ".."};
    for (int slot = 0; slot < 2; slot++) for (int i = 0; i < 3; i++) {
        reset_added();
        int got = invoke("/owned", slot == 0 ? invalid[i] : "old.wav",
            slot == 1 ? invalid[i] : "new.wav");
        char name[80]; snprintf(name, sizeof(name), "invalid-basename-slot-%d-partition-%d", slot, i);
        report_added(name, got == EINVAL && alloc_calls == 3 && frees == 3 &&
            open_calls == 0 && move_calls == 0 && close_calls == 0, got, EINVAL);
    }
    reset_added();
    int got = invoke("relative", "old.wav", "new.wav");
    report_added("relative-parent-rejected-before-open", got == EINVAL && frees == 3 &&
        open_calls == 0 && move_calls == 0 && close_calls == 0, got, EINVAL);
    const int native_errors[] = {ENOSYS, EOPNOTSUPP, EINVAL};
    for (int i = 0; i < 3; i++) {
        reset_added(); open_error = native_errors[i];
        got = invoke("/owned", "old.wav", "new.wav");
        char name[80]; snprintf(name, sizeof(name), "open-error-%d-cannot-admit-copy", native_errors[i]);
        report_added(name, got == native_errors[i] && open_calls == 1 &&
            move_calls == 0 && close_calls == 0 && frees == 3, got, native_errors[i]);
        reset_added(); stat_error = native_errors[i];
        got = invoke("/owned", "old.wav", "new.wav");
        snprintf(name, sizeof(name), "stat-error-%d-cannot-admit-copy", native_errors[i]);
        report_added(name, got == native_errors[i] && open_calls == 1 &&
            move_calls == 0 && close_calls == 1 && frees == 3, got, native_errors[i]);
    }
    reset_added(); nonregular = 1;
    got = invoke("/owned", "old.wav", "new.wav");
    report_added("symlink-source-rejected-before-rename", got == EINVAL &&
        move_calls == 0 && close_calls == 1 && frees == 3, got, EINVAL);
    reset_added();
    check_case("enosys-rename-admits-copy", ENOSYS, 0, 0, -ENOSYS, 1);
    reset_added();
    check_case("einval-rename-admits-copy", EINVAL, 0, 0, -EINVAL, 1);
    reset_added();
}
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    check_case("success", 0, 0, 0, 0, 1);
    check_case("pre-close-name-rejection", ENAMETOOLONG, 0, 0, 65536, 1);
    check_case("pre-close-name-rejection-preserves-primary", ENAMETOOLONG, EIO, 0, 65536, 1);
    check_case("post-success-close-name-error-uncertain", 0, ENAMETOOLONG, 0, ENAMETOOLONG, 1);
    check_case("unsupported-admission", EOPNOTSUPP, 0, 0, -EOPNOTSUPP, 1);
    check_case("unsupported-close-error-blocks-copy", EOPNOTSUPP, EIO, 0, EIO, 1);
    check_case("ordinary-syscall-primary-preserved", EIO, ENAMETOOLONG, 0, EIO, 1);
    check_case("pre-move-stat-name-rejection", 0, EIO, ENAMETOOLONG, 65536, 0);
    check_case("collision-unchanged", EEXIST, 0, 0, EEXIST, 1);
    check_case("not-found-unchanged", ENOENT, 0, 0, ENOENT, 1);

    added_admission_cases();

    char source[4096];
    if (snprintf(source, sizeof(source), "%s/owned.wav", argv[1]) >= (int)sizeof(source)) return 2;
    int fd = open(source, O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC, 0600);
    if (fd < 0) { perror("fixture source"); return 2; }
    ssize_t written = write(fd, "synthetic", 9);
    int write_error = written != 9 ? (written < 0 ? errno : EIO) : 0;
    int closed = close(fd);
    int close_error_code = closed != 0 ? errno : 0;
    if (write_error || close_error_code) {
        fprintf(stderr, "fixture write errno=%d close errno=%d\n", write_error, close_error_code);
        return 2;
    }
    char target[261];
    for (int i = 0; i < 64; i++) memcpy(target + i * 4, "\xf0\x9f\x98\x80", 4);
    memcpy(target + 256, ".wav", 5);
    actual_io = 1; move_calls = 0; close_calls = 0;
    int got = invoke(argv[1], "owned.wav", target);
    struct stat source_stat;
    int pass = got == 65536 && stat(source, &source_stat) == 0 && source_stat.st_size == 9 &&
        boundary_errors == 0 && ownership_errors == 0 && live_count == 0;
    printf("%s real-filesystem-64-emoji-plus-extension status=%d expected=65536 source_preserved=%d\n",
           pass ? "PASS" : "FAIL", got, stat(source, &source_stat) == 0);
    cases++; if (!pass) failures++;
    printf("cases=%d failures=%d\n", cases, failures);
    return failures ? 1 : 0;
}
