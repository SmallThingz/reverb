#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/fs.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef SYS_renameat2
#error "The selected Android ABI must expose SYS_renameat2"
#endif

/* Outside Linux errno range: pathname rejection was observed before directory close. */
#define REVERB_MOVE_NAME_TOO_LONG 65536

/* JNI strings use modified UTF-8; receive strictly encoded standard UTF-8 bytes instead. */
static char *path_bytes(JNIEnv *env, jbyteArray input, int *error) {
    if (input == NULL) { *error = EINVAL; return NULL; }
    jsize length = (*env)->GetArrayLength(env, input);
    if (length == 0) { *error = EINVAL; return NULL; }
    char *result = malloc((size_t)length + 1);
    if (result == NULL) { *error = ENOMEM; return NULL; }
    (*env)->GetByteArrayRegion(env, input, 0, length, (jbyte *)result);
    if ((*env)->ExceptionCheck(env)) { free(result); *error = EFAULT; return NULL; }
    if (memchr(result, '\0', (size_t)length) != NULL) {
        free(result); *error = EINVAL; return NULL;
    }
    result[length] = '\0';
    return result;
}

static int basename_valid(const char *name) {
    return strchr(name, '/') == NULL && strcmp(name, ".") != 0 && strcmp(name, "..") != 0;
}

JNIEXPORT jint JNICALL
Java_app_smallthingz_reverb_NoReplaceMoveNative_renameNoReplace(
        JNIEnv *env, jobject receiver, jbyteArray parent_bytes,
        jbyteArray source_bytes, jbyteArray destination_bytes) {
    (void)receiver;
    int result = 0;
    int directory = -1;
    char *parent = NULL, *source = NULL, *destination = NULL;
    parent = path_bytes(env, parent_bytes, &result);
    if (parent == NULL) goto done;
    source = path_bytes(env, source_bytes, &result);
    if (source == NULL) goto done;
    destination = path_bytes(env, destination_bytes, &result);
    if (destination == NULL) goto done;
    if (parent[0] != '/' || !basename_valid(source) || !basename_valid(destination)) {
        result = EINVAL; goto done;
    }
    directory = open(parent, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    if (directory < 0) { result = errno; goto done; }
    struct stat source_stat;
    if (fstatat(directory, source, &source_stat, AT_SYMLINK_NOFOLLOW) != 0) {
        result = errno; goto done;
    }
    if (!S_ISREG(source_stat.st_mode)) { result = EINVAL; goto done; }
    /* libc renameat2 starts at API 30; the public syscall ABI also works at minSdk 28. */
    if (syscall(SYS_renameat2, directory, source, directory, destination, RENAME_NOREPLACE) != 0) {
        result = errno;
        /* Only this validated, fixed-flag operation can report unsupported, not open/fstat. */
        if (result == ENOSYS || result == EOPNOTSUPP || result == EINVAL) result = -result;
    }
done:
    if (result == ENAMETOOLONG) result = REVERB_MOVE_NAME_TOO_LONG;
    /* Never retry close. A close failure must not turn an uncertain result into copy admission. */
    if (directory >= 0 && close(directory) != 0 && result <= 0) result = errno;
    free(destination);
    free(source);
    free(parent);
    return result;
}
