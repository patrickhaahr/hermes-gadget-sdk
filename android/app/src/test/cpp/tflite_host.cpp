// Test-only: runs a TensorFlow Lite model through the TensorFlow Lite C API,
// loaded at run time from the library path the test passes in. The app runs
// the same models with LiteRT on the phone (LiteRtModel.kt); this lets the JVM
// tests drive the production wake pipeline with the real models.
#include <dlfcn.h>
#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace {

// The few C API entry points used (tensorflow/lite/core/c/c_api.h), resolved by name.
struct Api {
  void* (*model_create)(const void*, size_t);
  void (*model_delete)(void*);
  void* (*interpreter_create)(const void*, const void*);
  void (*interpreter_delete)(void*);
  int (*resize_input)(void*, int32_t, const int*, int32_t);
  int (*allocate)(void*);
  int (*invoke)(void*);
  void* (*input_tensor)(const void*, int32_t);
  const void* (*output_tensor)(const void*, int32_t);
  int32_t (*num_dims)(const void*);
  int32_t (*dim)(const void*, int32_t);
  size_t (*byte_size)(const void*);
  int (*copy_from)(void*, const void*, size_t);
  int (*copy_to)(const void*, void*, size_t);
};

template <typename F>
bool bind(void* lib, F& fn, const char* name) {
  fn = reinterpret_cast<F>(dlsym(lib, name));
  return fn != nullptr;
}

const Api* api(const std::string& path) {
  static Api a{};
  static void* lib = nullptr;
  if (lib) return &a;
  lib = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
  if (!lib) return nullptr;
  bool ok = bind(lib, a.model_create, "TfLiteModelCreate") && bind(lib, a.model_delete, "TfLiteModelDelete") &&
            bind(lib, a.interpreter_create, "TfLiteInterpreterCreate") &&
            bind(lib, a.interpreter_delete, "TfLiteInterpreterDelete") &&
            bind(lib, a.resize_input, "TfLiteInterpreterResizeInputTensor") &&
            bind(lib, a.allocate, "TfLiteInterpreterAllocateTensors") &&
            bind(lib, a.invoke, "TfLiteInterpreterInvoke") &&
            bind(lib, a.input_tensor, "TfLiteInterpreterGetInputTensor") &&
            bind(lib, a.output_tensor, "TfLiteInterpreterGetOutputTensor") &&
            bind(lib, a.num_dims, "TfLiteTensorNumDims") && bind(lib, a.dim, "TfLiteTensorDim") &&
            bind(lib, a.byte_size, "TfLiteTensorByteSize") && bind(lib, a.copy_from, "TfLiteTensorCopyFromBuffer") &&
            bind(lib, a.copy_to, "TfLiteTensorCopyToBuffer");
  if (!ok) {
    dlclose(lib);
    lib = nullptr;
    return nullptr;
  }
  return &a;
}

struct Model {
  const Api* api;
  std::vector<char> bytes;  // TfLiteModelCreate keeps a pointer into this
  void* model;
  void* interpreter;
  bool allocated;
};

// Models with a dynamic input can't allocate until it is resized.
bool allocate(Model* m) {
  if (!m->allocated) m->allocated = m->api->allocate(m->interpreter) == 0;
  return m->allocated;
}

Model* model(jlong handle) { return reinterpret_cast<Model*>(handle); }

}  // namespace

#define HG_TFLITE(ret, name) \
  extern "C" JNIEXPORT ret JNICALL Java_io_github_adolanium_hermesgadget_HostTfliteModel_##name

HG_TFLITE(jlong, nativeOpen)(JNIEnv* env, jclass, jstring library, jbyteArray bytes) {
  const char* path = env->GetStringUTFChars(library, nullptr);
  const Api* a = api(path);
  env->ReleaseStringUTFChars(library, path);
  if (!a) return 0;
  auto* m = new Model{a, std::vector<char>(static_cast<size_t>(env->GetArrayLength(bytes))), nullptr, nullptr, false};
  env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(m->bytes.size()), reinterpret_cast<jbyte*>(m->bytes.data()));
  m->model = a->model_create(m->bytes.data(), m->bytes.size());
  m->interpreter = m->model ? a->interpreter_create(m->model, nullptr) : nullptr;
  if (!m->interpreter) {
    if (m->interpreter) a->interpreter_delete(m->interpreter);
    if (m->model) a->model_delete(m->model);
    delete m;
    return 0;
  }
  return reinterpret_cast<jlong>(m);
}

HG_TFLITE(jboolean, nativeResize)(JNIEnv* env, jclass, jlong handle, jintArray shape) {
  Model* m = model(handle);
  std::vector<int> dims(static_cast<size_t>(env->GetArrayLength(shape)));
  env->GetIntArrayRegion(shape, 0, static_cast<jsize>(dims.size()), dims.data());
  m->allocated = false;
  return m->api->resize_input(m->interpreter, 0, dims.data(), static_cast<int32_t>(dims.size())) == 0 && allocate(m);
}

HG_TFLITE(jintArray, nativeInputShape)(JNIEnv* env, jclass, jlong handle) {
  Model* m = model(handle);
  const void* t = m->api->input_tensor(m->interpreter, 0);
  int32_t n = m->api->num_dims(t);
  jintArray out = env->NewIntArray(n);
  for (int32_t i = 0; i < n; ++i) {
    jint d = m->api->dim(t, i);
    env->SetIntArrayRegion(out, i, 1, &d);
  }
  return out;
}

HG_TFLITE(jint, nativeOutputSize)(JNIEnv*, jclass, jlong handle) {
  Model* m = model(handle);
  if (!allocate(m)) return -1;
  return static_cast<jint>(m->api->byte_size(m->api->output_tensor(m->interpreter, 0)) / sizeof(float));
}

HG_TFLITE(jboolean, nativeRun)(JNIEnv* env, jclass, jlong handle, jfloatArray input, jfloatArray output) {
  Model* m = model(handle);
  std::vector<float> in(static_cast<size_t>(env->GetArrayLength(input)));
  env->GetFloatArrayRegion(input, 0, static_cast<jsize>(in.size()), in.data());
  if (!allocate(m) ||
      m->api->copy_from(m->api->input_tensor(m->interpreter, 0), in.data(), in.size() * sizeof(float)) != 0 ||
      m->api->invoke(m->interpreter) != 0) {
    return JNI_FALSE;
  }
  std::vector<float> out(static_cast<size_t>(env->GetArrayLength(output)));
  if (m->api->copy_to(m->api->output_tensor(m->interpreter, 0), out.data(), out.size() * sizeof(float)) != 0) {
    return JNI_FALSE;
  }
  env->SetFloatArrayRegion(output, 0, static_cast<jsize>(out.size()), out.data());
  return JNI_TRUE;
}

HG_TFLITE(void, nativeClose)(JNIEnv*, jclass, jlong handle) {
  Model* m = model(handle);
  m->api->interpreter_delete(m->interpreter);
  m->api->model_delete(m->model);
  delete m;
}
