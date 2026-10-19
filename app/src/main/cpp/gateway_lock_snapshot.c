#define _GNU_SOURCE
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define MAX_LOCK_BYTES (16 * 1024)

static int copy_path(JNIEnv *env, jbyteArray bytes, char *target) {
    if (bytes == NULL) return 0;
    const jsize length = (*env)->GetArrayLength(env, bytes);
    if (length <= 0 || length >= PATH_MAX) return 0;
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *)target);
    if ((*env)->ExceptionCheck(env) || memchr(target, '\0', (size_t)length) != NULL) return 0;
    target[length] = '\0';
    return 1;
}

/* Every directory is opened without following links, including canonical root path components. */
static int descend(int directory, const char *path, size_t length) {
    size_t start = 0;
    while (start < length) {
        size_t end = start;
        while (end < length && path[end] != '/') ++end;
        const size_t count = end - start;
        if (count == 0 || (count == 1 && path[start] == '.') ||
            (count == 2 && path[start] == '.' && path[start + 1] == '.')) {
            close(directory);
            return -1;
        }
        char component[PATH_MAX];
        memcpy(component, path + start, count);
        component[count] = '\0';
        const int next = openat(directory, component, O_PATH | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
        close(directory);
        if (next < 0) return -1;
        directory = next;
        start = end + 1;
    }
    return directory;
}

static int open_parent(const char *root, const char *relative, const char **name) {
    const size_t root_length = strlen(root);
    if (root[0] != '/' || root_length <= 1 || root[root_length - 1] == '/' || relative[0] == '/') return -1;
    int directory = open("/", O_PATH | O_DIRECTORY | O_CLOEXEC);
    if (directory < 0) return -1;
    directory = descend(directory, root + 1, root_length - 1);
    if (directory < 0) return -1;
    const char *last_slash = strrchr(relative, '/');
    *name = last_slash == NULL ? relative : last_slash + 1;
    if ((*name)[0] == '\0' || strcmp(*name, ".") == 0 || strcmp(*name, "..") == 0) {
        close(directory);
        return -1;
    }
    if (last_slash != NULL) directory = descend(directory, relative, (size_t)(last_slash - relative));
    return directory;
}

static int eligible(const struct stat *value, int max_bytes) {
    return S_ISREG(value->st_mode) && value->st_nlink == 1 &&
        value->st_size > 0 && value->st_size <= max_bytes &&
        value->st_mtim.tv_nsec >= 0 && value->st_mtim.tv_nsec < 1000000000 &&
        value->st_ctim.tv_nsec >= 0 && value->st_ctim.tv_nsec < 1000000000;
}

static int same_snapshot(const struct stat *a, const struct stat *b, int max_bytes) {
    return eligible(b, max_bytes) && a->st_dev == b->st_dev && a->st_ino == b->st_ino &&
        a->st_uid == b->st_uid && a->st_gid == b->st_gid && a->st_mode == b->st_mode &&
        a->st_size == b->st_size && a->st_mtim.tv_sec == b->st_mtim.tv_sec &&
        a->st_mtim.tv_nsec == b->st_mtim.tv_nsec && a->st_ctim.tv_sec == b->st_ctim.tv_sec &&
        a->st_ctim.tv_nsec == b->st_ctim.tv_nsec;
}

/* Node's bigint stat has no signed-64-bit nanosecond range restriction. */
static jstring nanoseconds(JNIEnv *env, const struct timespec *value) {
    __int128 total = (__int128)value->tv_sec * 1000000000 + value->tv_nsec;
    const int negative = total < 0;
    unsigned __int128 remaining = negative ? (unsigned __int128)(-total) : (unsigned __int128)total;
    char text[48];
    char *cursor = text + sizeof(text) - 1;
    *cursor = '\0';
    do {
        *--cursor = (char)('0' + remaining % 10);
        remaining /= 10;
    } while (remaining != 0);
    if (negative) *--cursor = '-';
    return (*env)->NewStringUTF(env, cursor);
}

JNIEXPORT jobjectArray JNICALL
Java_com_coderred_andclaw_proroot_OpenClawLockSnapshotReader_nativeRead(
    JNIEnv *env, jobject instance, jbyteArray root_bytes, jbyteArray relative_bytes, jint max_bytes) {
    (void)instance;
    if (max_bytes <= 0 || max_bytes > MAX_LOCK_BYTES) return NULL;
    char root[PATH_MAX];
    char relative[PATH_MAX];
    if (!copy_path(env, root_bytes, root) || !copy_path(env, relative_bytes, relative)) return NULL;
    const char *name;
    int parent = open_parent(root, relative, &name);
    if (parent < 0) return NULL;
    int file = -1;
    int current_parent = -1;
    jobjectArray result = NULL;
    struct stat before, opened, after, current;
    if (fstatat(parent, name, &before, AT_SYMLINK_NOFOLLOW) != 0 || !eligible(&before, max_bytes)) goto finished;
    file = openat(parent, name, O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC);
    if (file < 0 || fstat(file, &opened) != 0 || !same_snapshot(&before, &opened, max_bytes)) goto finished;
    unsigned char bytes[MAX_LOCK_BYTES + 1];
    size_t count = 0;
    const size_t capacity = (size_t)before.st_size + 1;
    while (count < capacity) {
        const ssize_t size = read(file, bytes + count, capacity - count);
        if (size < 0) {
            if (errno == EINTR) continue;
            goto finished;
        }
        if (size == 0) break;
        count += (size_t)size;
    }
    if (count != (size_t)before.st_size || fstat(file, &after) != 0 ||
        !same_snapshot(&before, &after, max_bytes)) goto finished;
    /* Rewalk the current path: a renamed/replaced parent must not validate an old open fd. */
    current_parent = open_parent(root, relative, &name);
    if (current_parent < 0 || fstatat(current_parent, name, &current, AT_SYMLINK_NOFOLLOW) != 0 ||
        !same_snapshot(&before, &current, max_bytes)) goto finished;
    jclass object_class = (*env)->FindClass(env, "java/lang/Object");
    if (object_class == NULL) goto finished;
    result = (*env)->NewObjectArray(env, 5, object_class, NULL);
    if (result == NULL) goto finished;
    jbyteArray raw = (*env)->NewByteArray(env, (jsize)count);
    if (raw == NULL) goto finished;
    (*env)->SetByteArrayRegion(env, raw, 0, (jsize)count, (const jbyte *)bytes);
    if ((*env)->ExceptionCheck(env)) goto finished;
    (*env)->SetObjectArrayElement(env, result, 0, raw);
    char decimal[32];
    snprintf(decimal, sizeof(decimal), "%" PRIuMAX, (uintmax_t)before.st_dev);
    jstring dev = (*env)->NewStringUTF(env, decimal);
    if (dev == NULL) goto finished;
    (*env)->SetObjectArrayElement(env, result, 1, dev);
    snprintf(decimal, sizeof(decimal), "%" PRIuMAX, (uintmax_t)before.st_ino);
    jstring ino = (*env)->NewStringUTF(env, decimal);
    if (ino == NULL) goto finished;
    (*env)->SetObjectArrayElement(env, result, 2, ino);
    jstring mtime = nanoseconds(env, &before.st_mtim);
    if (mtime == NULL) goto finished;
    (*env)->SetObjectArrayElement(env, result, 3, mtime);
    jstring ctime = nanoseconds(env, &before.st_ctim);
    if (ctime == NULL) goto finished;
    (*env)->SetObjectArrayElement(env, result, 4, ctime);
finished:
    if (current_parent >= 0) close(current_parent);
    if (file >= 0) close(file);
    close(parent);
    return (*env)->ExceptionCheck(env) ? NULL : result;
}
