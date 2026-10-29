// Test-only, host-side stand-in for the Android NDK <jni.h>.
//
// WHY THIS EXISTS
// ---------------
// src/main/cpp/jni_bridge.cpp is the trust boundary of the app: it turns
// Java-supplied jint / jstring / jobject values into raw pointer arithmetic over
// direct ByteBuffers. Its input validation can only be exercised if the test
// controls what the VM does when a request is malformed, and those cases are
// exactly the ones that cannot be provoked from the JVM:
//
//   * GetStringUTFChars returning NULL (allocation failure),
//   * GetObjectArrayElement with an index past the end of the array,
//   * a direct ByteBuffer whose capacity is smaller than the byte count the
//     caller assumes.
//
// The file under test is compiled verbatim into the test binary (the test
// #includes it) so these entry points are driven against the production
// validation code, not against a copy of it.
//
// FIDELITY CONTRACT
// -----------------
// Only the entry points jni_bridge.cpp actually calls are implemented. Each one
// reproduces the behaviour the JNI specification mandates:
//
//   GetStringUTFChars       Returns NULL and throws OutOfMemoryError if the
//                           string cannot be converted. A caller that uses or
//                           releases the NULL result is violating the spec.
//   ReleaseStringUTFChars   Requires the pointer GetStringUTFChars returned for
//                           that same string. Real ART dereferences it, so a
//                           NULL release is undefined behaviour; the stub
//                           RECORDS it (EnvState::releaseWithNullChars)
//                           instead of crashing, so the suite can assert on it.
//   GetObjectArrayElement   Throws ArrayIndexOutOfBoundsException and returns
//                           NULL when index >= GetArrayLength().
//   GetDirectBufferCapacity Returns -1 when the object is not a direct buffer.
//   Every entry point        Counts itself in
//                           EnvState::callsWhileExceptionPending when an
//                           exception is already pending, because the spec
//                           forbids calling most JNI functions in that state.
//
// NOT part of the shipped library: app/src/main/cpp/CMakeLists.txt compiles
// jni_bridge.cpp against the real NDK <jni.h> and links -llog. Only
// src/test/cpp compiles with -I jni_stubs.

#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

// ---------------------------------------------------------------------------
// Primitive JNI types (same widths as the NDK headers on every Android ABI).
// ---------------------------------------------------------------------------

typedef int32_t jint;
typedef int64_t jlong;
typedef int8_t jbyte;
typedef uint8_t jboolean;
typedef float jfloat;
typedef double jdouble;
typedef jint jsize;

struct _jobject;
typedef _jobject* jobject;
typedef jobject jclass;
typedef jobject jstring;
typedef jobject jarray;
typedef jobject jobjectArray;
typedef jobject jfloatArray;
typedef jobject jintArray;
typedef jobject jbyteArray;
typedef jobject jthrowable;

struct _jobject {
    virtual ~_jobject() = default;
};

#define JNI_FALSE 0
#define JNI_TRUE 1

#define JNI_OK 0
#define JNI_ERR (-1)

#define JNI_VERSION_1_6 0x00010006

#define JNIEXPORT __attribute__((visibility("default")))
#define JNICALL

typedef struct JNINativeMethod {
    const char* name;
    const char* signature;
    void* fnPtr;
} JNINativeMethod;

namespace commix_jni_stub {

/** A java.lang.String handed across the boundary. */
struct StringObject : _jobject {
    std::string utf8;
    // When true, GetStringUTFChars models an allocation failure: it returns
    // NULL and raises OutOfMemoryError, exactly as ART does under memory
    // pressure. ReleaseStringUTFChars is never legal for such a string.
    bool getCharsFails = false;
};

/** A java.nio.ByteBuffer allocated with allocateDirect(). */
struct BufferObject : _jobject {
    void* address = nullptr;
    // GetDirectBufferCapacity() value. -1 models a non-direct buffer, which the
    // JNI spec reports as a negative capacity.
    jlong capacity = 0;
};

/** A java.lang.Object[] of ByteBuffers (Kotlin Array<ByteBuffer>). */
struct ObjectArrayObject : _jobject {
    std::vector<jobject> elements;
};

struct FloatArrayObject : _jobject {
    std::vector<jfloat> data;
};

struct IntArrayObject : _jobject {
    std::vector<jint> data;
};

/**
 * Observable state of the fake VM. Tests assert on these counters, which is how
 * "the native code stopped touching JNI after the exception" becomes a testable
 * claim instead of an inspection.
 */
struct EnvState {
    bool exceptionPending = false;
    std::string exceptionType;

    int callsWhileExceptionPending = 0;
    int getArrayLengthCalls = 0;
    int getObjectArrayElementCalls = 0;
    int outOfBoundsElementCalls = 0;
    int deleteLocalRefCalls = 0;
    int getStringUTFCharsCalls = 0;
    int releaseStringUTFCharsCalls = 0;
    int releaseWithNullChars = 0;
    int findClassCalls = 0;
    int registerNativesCalls = 0;
    int setFloatArrayRegionCalls = 0;
    int setIntArrayRegionCalls = 0;

    void reset() { *this = EnvState(); }
};

inline EnvState& envState() {
    static EnvState state;
    return state;
}

inline void raiseException(const char* type) {
    envState().exceptionPending = true;
    envState().exceptionType = type;
}

// Called at the top of every entry point that the JNI spec forbids calling
// while an exception is pending.
inline void noteCall() {
    if (envState().exceptionPending) envState().callsWhileExceptionPending++;
}

} // namespace commix_jni_stub

/** The JavaVM handle (only GetEnv is reachable from jni_bridge.cpp). */
struct JavaVM {
    jint (*GetEnv)(void** penv, jint version) = nullptr;
};

struct _JNIEnv;
typedef _JNIEnv JNIEnv;

/**
 * The environment table. Every member is a plain member function so that the
 * production code's `env->Foo(...)` syntax compiles unchanged against it.
 */
struct _JNIEnv {
    // --- Direct buffers -----------------------------------------------------

    void* GetDirectBufferAddress(jobject buf) {
        commix_jni_stub::noteCall();
        auto* b = static_cast<commix_jni_stub::BufferObject*>(buf);
        if (!b || b->capacity < 0) return nullptr;
        return b->address;
    }

    jlong GetDirectBufferCapacity(jobject buf) {
        commix_jni_stub::noteCall();
        auto* b = static_cast<commix_jni_stub::BufferObject*>(buf);
        return b ? b->capacity : -1;
    }

    // --- Arrays -------------------------------------------------------------

    jsize GetArrayLength(jarray array) {
        commix_jni_stub::noteCall();
        commix_jni_stub::envState().getArrayLengthCalls++;
        if (!array) return 0;
        if (auto* oa = dynamic_cast<commix_jni_stub::ObjectArrayObject*>(array)) {
            return static_cast<jsize>(oa->elements.size());
        }
        if (auto* fa = dynamic_cast<commix_jni_stub::FloatArrayObject*>(array)) {
            return static_cast<jsize>(fa->data.size());
        }
        if (auto* ia = dynamic_cast<commix_jni_stub::IntArrayObject*>(array)) {
            return static_cast<jsize>(ia->data.size());
        }
        return 0;
    }

    jobject GetObjectArrayElement(jobjectArray array, jsize index) {
        commix_jni_stub::noteCall();
        auto& st = commix_jni_stub::envState();
        st.getObjectArrayElementCalls++;
        auto* oa = static_cast<commix_jni_stub::ObjectArrayObject*>(array);
        if (!oa || index < 0 || index >= static_cast<jsize>(oa->elements.size())) {
            st.outOfBoundsElementCalls++;
            commix_jni_stub::raiseException("java.lang.ArrayIndexOutOfBoundsException");
            return nullptr;
        }
        return oa->elements[static_cast<size_t>(index)];
    }

    void DeleteLocalRef(jobject ref) {
        // Legal even with an exception pending, so deliberately not counted by
        // noteCall().
        commix_jni_stub::envState().deleteLocalRefCalls++;
        (void)ref;
    }

    void SetFloatArrayRegion(jfloatArray array, jsize start, jsize len, const jfloat* values) {
        commix_jni_stub::noteCall();
        commix_jni_stub::envState().setFloatArrayRegionCalls++;
        auto* fa = static_cast<commix_jni_stub::FloatArrayObject*>(array);
        if (!fa || start < 0 || len < 0 ||
            static_cast<size_t>(start) + static_cast<size_t>(len) > fa->data.size()) {
            commix_jni_stub::raiseException("java.lang.ArrayIndexOutOfBoundsException");
            return;
        }
        for (jsize i = 0; i < len; ++i) fa->data[static_cast<size_t>(start + i)] = values[i];
    }

    void SetIntArrayRegion(jintArray array, jsize start, jsize len, const jint* values) {
        commix_jni_stub::noteCall();
        commix_jni_stub::envState().setIntArrayRegionCalls++;
        auto* ia = static_cast<commix_jni_stub::IntArrayObject*>(array);
        if (!ia || start < 0 || len < 0 ||
            static_cast<size_t>(start) + static_cast<size_t>(len) > ia->data.size()) {
            commix_jni_stub::raiseException("java.lang.ArrayIndexOutOfBoundsException");
            return;
        }
        for (jsize i = 0; i < len; ++i) ia->data[static_cast<size_t>(start + i)] = values[i];
    }

    // --- Strings ------------------------------------------------------------

    const char* GetStringUTFChars(jstring str, jboolean* isCopy) {
        commix_jni_stub::noteCall();
        auto& st = commix_jni_stub::envState();
        st.getStringUTFCharsCalls++;
        auto* s = static_cast<commix_jni_stub::StringObject*>(str);
        if (!s || s->getCharsFails) {
            // JNI spec: NULL return always comes with OutOfMemoryError pending.
            commix_jni_stub::raiseException("java.lang.OutOfMemoryError");
            return nullptr;
        }
        if (isCopy) *isCopy = JNI_TRUE;
        return s->utf8.c_str();
    }

    void ReleaseStringUTFChars(jstring str, const char* chars) {
        auto& st = commix_jni_stub::envState();
        st.releaseStringUTFCharsCalls++;
        (void)str;
        if (chars == nullptr) {
            // Real ART would dereference this. Record instead of crashing so the
            // suite can report "native code released a NULL string pointer" as
            // a readable failure.
            st.releaseWithNullChars++;
            return;
        }
        commix_jni_stub::noteCall();
    }

    // --- Exceptions ---------------------------------------------------------

    jboolean ExceptionCheck() {
        return commix_jni_stub::envState().exceptionPending ? JNI_TRUE : JNI_FALSE;
    }

    void ExceptionDescribe() {
        (void)commix_jni_stub::envState().exceptionType;
    }

    void ExceptionClear() {
        commix_jni_stub::envState().exceptionPending = false;
        commix_jni_stub::envState().exceptionType.clear();
    }

    // --- Class registration (JNI_OnLoad) ------------------------------------

    jclass FindClass(const char* name) {
        commix_jni_stub::noteCall();
        commix_jni_stub::envState().findClassCalls++;
        (void)name;
        // Any non-null handle stands in for a found class.
        static commix_jni_stub::StringObject placeholder;
        return &placeholder;
    }

    jint RegisterNatives(jclass clazz, const JNINativeMethod* methods, jint count) {
        (void)clazz;
        (void)methods;
        commix_jni_stub::envState().registerNativesCalls++;
        (void)count;
        return JNI_OK;
    }
};