// A pty for the console's terminal mode: openpty + fork/exec the rootfs launcher, return the master fd.
#include <jni.h>
#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

static char **to_c_array(JNIEnv *env, jobjectArray arr) {
    if (arr == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = calloc((size_t) n + 1, sizeof(char *));
    if (out == NULL) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        if (s == NULL) { out[i] = strdup(""); continue; }
        const char *c = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(c != NULL ? c : "");
        if (c != NULL) (*env)->ReleaseStringUTFChars(env, s, c);
        (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static void free_c_array(char **a) {
    if (a == NULL) return;
    for (char **p = a; *p != NULL; p++) free(*p);
    free(a);
}

// Returns (pid << 32) | master fd, or -1. The child runs argv[0] with argv and env, in its own session, as the pty's
// controlling terminal, so bash and full-screen programs see a real tty.
JNIEXPORT jlong JNICALL
Java_app_aomarchy_Native_nativePtyStart(JNIEnv *env, jclass clazz, jobjectArray argv_arr,
                                         jobjectArray env_arr, jstring cwd_str, jint cols, jint rows) {
    int master = -1, slave = -1;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    if (openpty(&master, &slave, NULL, NULL, &ws) != 0) return -1;

    pid_t pid = fork();
    if (pid < 0) {
        close(master);
        close(slave);
        return -1;
    }
    if (pid == 0) {
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        close(master);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);
        const char *cwd = NULL;
        if (cwd_str != NULL) cwd = (*env)->GetStringUTFChars(env, cwd_str, NULL);
        if (cwd != NULL) chdir(cwd);
        char **argv = to_c_array(env, argv_arr);
        char **envp = to_c_array(env, env_arr);
        if (argv == NULL || argv[0] == NULL) _exit(127);
        execve(argv[0], argv, envp);
        _exit(127);
    }
    close(slave);
    return ((jlong) pid << 32) | (jlong) (unsigned int) master;
}

JNIEXPORT void JNICALL
Java_app_aomarchy_Native_nativePtyResize(JNIEnv *env, jclass clazz, jint fd, jint cols, jint rows) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT void JNICALL
Java_app_aomarchy_Native_nativePtyClose(JNIEnv *env, jclass clazz, jint fd) {
    if (fd >= 0) close(fd);
}
