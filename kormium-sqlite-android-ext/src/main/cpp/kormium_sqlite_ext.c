// Registers a SQLite extension with androidx.sqlite's bundled SQLite.
//
// androidx.sqlite's BundledSQLiteDriver never exposes the underlying `sqlite3*`, so an extension
// cannot be loaded per connection from Kotlin. Its native library does, however, export
// sqlite3_auto_extension (verified with `nm -D libsqliteJni.so` on sqlite-bundled 2.7.0), and that
// takes only a function pointer: registering an entry point there applies it to every connection
// opened afterwards, which covers the whole pool. This shim is the ~40 lines of C needed to reach
// it, and it is extension-agnostic — an extension package ships only its own .so.
//
// The extension is dlopen'd in its ordinary loadable form (SQLITE_EXTENSION_INIT2), so it needs no
// direct sqlite3_* symbols: SQLite hands auto-registered entry points the API routines table
// (see sqlite3.c, sqlite3AutoLoadExtensions).
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>

typedef int (*kormium_auto_extension_fn)(void (*)(void));

static jstring fail(JNIEnv *env, const char *what, const char *detail) {
    char buffer[512];
    snprintf(buffer, sizeof buffer, "%s: %s", what, detail ? detail : "unknown error");
    return (*env)->NewStringUTF(env, buffer);
}

// Returns null on success, or a message describing what went wrong.
JNIEXPORT jstring JNICALL
Java_io_github_kormium_SqliteAndroidRegistrationScope_nativeRegister(
        JNIEnv *env, jobject thiz, jstring jPath, jstring jEntryPoint) {
    (void) thiz;

    // Already loaded by androidx by the time any database is opened; dlopen by soname returns a
    // handle to that same mapping rather than loading a second copy.
    void *sqlite = dlopen("libsqliteJni.so", RTLD_NOW);
    if (sqlite == NULL) return fail(env, "cannot open libsqliteJni.so", dlerror());

    kormium_auto_extension_fn autoExtension =
            (kormium_auto_extension_fn) dlsym(sqlite, "sqlite3_auto_extension");
    if (autoExtension == NULL) return fail(env, "libsqliteJni.so has no sqlite3_auto_extension", dlerror());

    const char *path = (*env)->GetStringUTFChars(env, jPath, NULL);
    void *extension = dlopen(path, RTLD_NOW);
    const char *openError = extension == NULL ? dlerror() : NULL;
    (*env)->ReleaseStringUTFChars(env, jPath, path);
    if (extension == NULL) return fail(env, "cannot open the extension library", openError);

    const char *entryPoint = (*env)->GetStringUTFChars(env, jEntryPoint, NULL);
    void *entry = dlsym(extension, entryPoint);
    const char *symbolError = entry == NULL ? dlerror() : NULL;
    (*env)->ReleaseStringUTFChars(env, jEntryPoint, entryPoint);
    if (entry == NULL) return fail(env, "the extension has no such entry point", symbolError);

    // Registering the same entry point twice is a harmless no-op in SQLite, so this needs no guard.
    int rc = autoExtension((void (*)(void)) entry);
    if (rc != 0) {
        char code[32];
        snprintf(code, sizeof code, "SQLite result code %d", rc);
        return fail(env, "sqlite3_auto_extension failed", code);
    }
    return NULL;
}
