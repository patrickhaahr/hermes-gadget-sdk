// JNI bridge: the hgsim C ABI (firmware/sim/include/hgsim.h) for the Android
// client. Every hgsim_* call, and so every host callback, runs on the Kotlin
// core thread, which the JVM already has attached.
//
// Text crosses the boundary as UTF-8 byte arrays rather than jstring: JNI's
// "modified UTF-8" rejects characters outside the BMP, which replies may hold.
#include <android/log.h>
#include <jni.h>

#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "hgsim.h"

namespace {

constexpr const char* kTag = "HermesGadgetCore";

struct Bridge {
  JNIEnv* env = nullptr;  // the core thread's env, valid while the instance lives
  jobject host = nullptr;
  jmethodID transport_connect, transport_send_text, transport_send_binary, transport_close;
  jmethodID display_flush, display_backlight;
  jmethodID mic_start, mic_stop;
  jmethodID speaker_begin, speaker_write, speaker_end, speaker_abort, speaker_busy, speaker_volume;
  jmethodID storage_get, storage_set, storage_erase;
  jmethodID now_ms, random_bytes, log;
  hgsim* sim = nullptr;
};

Bridge* bridge(void* user) { return static_cast<Bridge*>(user); }

// A Kotlin exception must never unwind into the core: log it and use the default.
bool failed(JNIEnv* env, const char* what) {
  if (!env->ExceptionCheck()) return false;
  env->ExceptionDescribe();
  env->ExceptionClear();
  __android_log_print(ANDROID_LOG_ERROR, kTag, "host callback %s failed", what);
  return true;
}

jbyteArray bytes(JNIEnv* env, const void* data, size_t len) {
  jbyteArray out = env->NewByteArray(static_cast<jsize>(len));
  if (out && len) env->SetByteArrayRegion(out, 0, static_cast<jsize>(len), static_cast<const jbyte*>(data));
  return out;
}

jbyteArray bytes(JNIEnv* env, const char* text) { return bytes(env, text, text ? std::strlen(text) : 0); }

// A UTF-8 byte array from Kotlin as a C string.
std::string text(JNIEnv* env, jbyteArray array) {
  if (!array) return {};
  jsize n = env->GetArrayLength(array);
  std::string out(static_cast<size_t>(n), '\0');
  env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte*>(out.data()));
  return out;
}

void call_void(void* user, jmethodID m, const char* what) {
  Bridge* b = bridge(user);
  b->env->CallVoidMethod(b->host, m);
  failed(b->env, what);
}

void call_void_int(void* user, jmethodID m, int value, const char* what) {
  Bridge* b = bridge(user);
  b->env->CallVoidMethod(b->host, m, static_cast<jint>(value));
  failed(b->env, what);
}

int call_bool_int(void* user, jmethodID m, jint value, const char* what) {
  Bridge* b = bridge(user);
  jboolean ok = b->env->CallBooleanMethod(b->host, m, value);
  return failed(b->env, what) ? 0 : ok == JNI_TRUE;
}

void cb_transport_connect(void* user, const char* url, const char* subprotocol) {
  Bridge* b = bridge(user);
  JNIEnv* env = b->env;
  jbyteArray u = bytes(env, url), s = bytes(env, subprotocol);
  env->CallVoidMethod(b->host, b->transport_connect, u, s);
  failed(env, "transportConnect");
  env->DeleteLocalRef(u);
  env->DeleteLocalRef(s);
}

int send(void* user, jmethodID m, const void* data, size_t len, const char* what) {
  Bridge* b = bridge(user);
  JNIEnv* env = b->env;
  jbyteArray payload = bytes(env, data, len);
  if (!payload) {
    failed(env, what);
    return 0;
  }
  jboolean ok = env->CallBooleanMethod(b->host, m, payload);
  env->DeleteLocalRef(payload);
  return failed(env, what) ? 0 : ok == JNI_TRUE;
}

int cb_send_text(void* user, const char* data, size_t len) {
  return send(user, bridge(user)->transport_send_text, data, len, "transportSendText");
}

int cb_send_binary(void* user, const uint8_t* data, size_t len) {
  return send(user, bridge(user)->transport_send_binary, data, len, "transportSendBinary");
}

void cb_transport_close(void* user) { call_void(user, bridge(user)->transport_close, "transportClose"); }

void cb_display_flush(void* user, int y0, int y1) {
  Bridge* b = bridge(user);
  b->env->CallVoidMethod(b->host, b->display_flush, static_cast<jint>(y0), static_cast<jint>(y1));
  failed(b->env, "displayFlush");
}

void cb_display_backlight(void* user, int percent) {
  call_void_int(user, bridge(user)->display_backlight, percent, "displayBacklight");
}

int cb_mic_start(void* user, uint32_t rate) {
  return call_bool_int(user, bridge(user)->mic_start, static_cast<jint>(rate), "micStart");
}

void cb_mic_stop(void* user) { call_void(user, bridge(user)->mic_stop, "micStop"); }

int cb_speaker_begin(void* user, uint32_t rate) {
  return call_bool_int(user, bridge(user)->speaker_begin, static_cast<jint>(rate), "speakerBegin");
}

void cb_speaker_write(void* user, const int16_t* samples, size_t count) {
  Bridge* b = bridge(user);
  JNIEnv* env = b->env;
  jshortArray pcm = env->NewShortArray(static_cast<jsize>(count));
  if (!pcm) {
    failed(env, "speakerWrite");
    return;
  }
  env->SetShortArrayRegion(pcm, 0, static_cast<jsize>(count), samples);
  env->CallVoidMethod(b->host, b->speaker_write, pcm);
  failed(env, "speakerWrite");
  env->DeleteLocalRef(pcm);
}

void cb_speaker_end(void* user) { call_void(user, bridge(user)->speaker_end, "speakerEnd"); }

void cb_speaker_abort(void* user) { call_void(user, bridge(user)->speaker_abort, "speakerAbort"); }

int cb_speaker_busy(void* user) {
  Bridge* b = bridge(user);
  jboolean busy = b->env->CallBooleanMethod(b->host, b->speaker_busy);
  return failed(b->env, "speakerBusy") ? 0 : busy == JNI_TRUE;
}

void cb_speaker_volume(void* user, int percent) {
  call_void_int(user, bridge(user)->speaker_volume, percent, "speakerVolume");
}

int cb_storage_get(void* user, const char* key, char* out, size_t cap) {
  Bridge* b = bridge(user);
  JNIEnv* env = b->env;
  jbyteArray k = bytes(env, key);
  auto value = static_cast<jbyteArray>(env->CallObjectMethod(b->host, b->storage_get, k));
  env->DeleteLocalRef(k);
  if (failed(env, "storageGet") || !value) return -1;
  std::string v = text(env, value);
  env->DeleteLocalRef(value);
  if (out && cap) {
    size_t n = v.size() < cap - 1 ? v.size() : cap - 1;
    std::memcpy(out, v.data(), n);
    out[n] = '\0';
  }
  return static_cast<int>(v.size());
}

void cb_storage_set(void* user, const char* key, const char* value) {
  Bridge* b = bridge(user);
  JNIEnv* env = b->env;
  jbyteArray k = bytes(env, key), v = bytes(env, value);
  env->CallVoidMethod(b->host, b->storage_set, k, v);
  failed(env, "storageSet");
  env->DeleteLocalRef(k);
  env->DeleteLocalRef(v);
}

void cb_storage_erase(void* user, const char* key) {
  Bridge* b = bridge(user);
  JNIEnv* env = b->env;
  jbyteArray k = bytes(env, key);
  env->CallVoidMethod(b->host, b->storage_erase, k);
  failed(env, "storageErase");
  env->DeleteLocalRef(k);
}

uint32_t cb_now_ms(void* user) {
  Bridge* b = bridge(user);
  jlong now = b->env->CallLongMethod(b->host, b->now_ms);
  return failed(b->env, "nowMs") ? 0 : static_cast<uint32_t>(now);
}

void cb_random_bytes(void* user, uint8_t* out, size_t len) {
  Bridge* b = bridge(user);
  JNIEnv* env = b->env;
  auto data = static_cast<jbyteArray>(env->CallObjectMethod(b->host, b->random_bytes, static_cast<jint>(len)));
  // The device key comes from here: never fall back to predictable bytes silently.
  if (failed(env, "randomBytes") || !data || static_cast<size_t>(env->GetArrayLength(data)) != len) {
    __android_log_print(ANDROID_LOG_FATAL, kTag, "no secure random bytes");
    std::abort();
  }
  env->GetByteArrayRegion(data, 0, static_cast<jsize>(len), reinterpret_cast<jbyte*>(out));
  env->DeleteLocalRef(data);
}

void cb_log(void* user, int level, const char* message) {
  Bridge* b = bridge(user);
  JNIEnv* env = b->env;
  jbyteArray m = bytes(env, message);
  env->CallVoidMethod(b->host, b->log, static_cast<jint>(level), m);
  failed(env, "log");
  env->DeleteLocalRef(m);
}

bool resolve(JNIEnv* env, Bridge* b) {
  jclass cls = env->GetObjectClass(b->host);
  struct {
    jmethodID* id;
    const char* name;
    const char* sig;
  } methods[] = {
      {&b->transport_connect, "transportConnect", "([B[B)V"},
      {&b->transport_send_text, "transportSendText", "([B)Z"},
      {&b->transport_send_binary, "transportSendBinary", "([B)Z"},
      {&b->transport_close, "transportClose", "()V"},
      {&b->display_flush, "displayFlush", "(II)V"},
      {&b->display_backlight, "displayBacklight", "(I)V"},
      {&b->mic_start, "micStart", "(I)Z"},
      {&b->mic_stop, "micStop", "()V"},
      {&b->speaker_begin, "speakerBegin", "(I)Z"},
      {&b->speaker_write, "speakerWrite", "([S)V"},
      {&b->speaker_end, "speakerEnd", "()V"},
      {&b->speaker_abort, "speakerAbort", "()V"},
      {&b->speaker_busy, "speakerBusy", "()Z"},
      {&b->speaker_volume, "speakerVolume", "(I)V"},
      {&b->storage_get, "storageGet", "([B)[B"},
      {&b->storage_set, "storageSet", "([B[B)V"},
      {&b->storage_erase, "storageErase", "([B)V"},
      {&b->now_ms, "nowMs", "()J"},
      {&b->random_bytes, "randomBytes", "(I)[B"},
      {&b->log, "log", "(I[B)V"},
  };
  for (auto& m : methods) {
    *m.id = env->GetMethodID(cls, m.name, m.sig);
    if (!*m.id) return false;  // NoSuchMethodError is pending for the caller
  }
  env->DeleteLocalRef(cls);
  return true;
}

// Callbacks use the env of the thread that made the call (always the core thread).
hgsim* sim(JNIEnv* env, jlong handle) {
  auto* b = reinterpret_cast<Bridge*>(handle);
  b->env = env;
  return b->sim;
}

}  // namespace

#define HG_JNI(ret, name) extern "C" JNIEXPORT ret JNICALL Java_io_github_adolanium_hermesgadget_NativeCore_##name

HG_JNI(jint, abiVersion)(JNIEnv*, jobject) { return hgsim_abi_version(); }

HG_JNI(jlong, create)(JNIEnv* env, jobject, jobject host, jint width, jint height, jboolean has_mic,
                      jboolean has_speaker, jint mic_rate, jint speaker_rate, jbyteArray board,
                      jbyteArray firmware, jbyteArray default_name, jbyteArray talk_label,
                      jbyteArray cancel_label, jboolean touch) {
  auto* b = new Bridge();
  b->env = env;
  b->host = env->NewGlobalRef(host);
  if (!resolve(env, b)) {
    env->DeleteGlobalRef(b->host);
    delete b;
    return 0;
  }
  // hgsim_create copies these into the core's profile before it returns.
  std::string board_s = text(env, board), firmware_s = text(env, firmware), name_s = text(env, default_name);
  std::string talk_s = text(env, talk_label), cancel_s = text(env, cancel_label);

  hgsim_config cfg{};
  cfg.width = width;
  cfg.height = height;
  cfg.has_mic = has_mic;
  cfg.has_speaker = has_speaker;
  cfg.has_backlight = 1;
  cfg.has_scroll_buttons = 0;
  cfg.mic_rate = static_cast<uint32_t>(mic_rate);
  cfg.speaker_rate = static_cast<uint32_t>(speaker_rate);
  cfg.board = board_s.c_str();
  cfg.firmware = firmware_s.c_str();
  cfg.default_name = name_s.c_str();
  cfg.talk_label = talk_label ? talk_s.c_str() : nullptr;
  cfg.cancel_label = cancel_label ? cancel_s.c_str() : nullptr;
  cfg.touch = touch;

  hgsim_host h{};
  h.user = b;
  h.transport_connect = cb_transport_connect;
  h.transport_send_text = cb_send_text;
  h.transport_send_binary = cb_send_binary;
  h.transport_close = cb_transport_close;
  h.display_flush = cb_display_flush;
  h.display_backlight = cb_display_backlight;
  h.mic_start = cb_mic_start;
  h.mic_stop = cb_mic_stop;
  h.speaker_begin = cb_speaker_begin;
  h.speaker_write = cb_speaker_write;
  h.speaker_end = cb_speaker_end;
  h.speaker_abort = cb_speaker_abort;
  h.speaker_busy = cb_speaker_busy;
  h.speaker_volume = cb_speaker_volume;
  h.storage_get = cb_storage_get;
  h.storage_set = cb_storage_set;
  h.storage_erase = cb_storage_erase;
  h.now_ms = cb_now_ms;
  h.random_bytes = cb_random_bytes;
  h.log = cb_log;

  b->sim = hgsim_create(&cfg, &h);
  if (!b->sim) {
    env->DeleteGlobalRef(b->host);
    delete b;
    return 0;
  }
  return reinterpret_cast<jlong>(b);
}

HG_JNI(void, destroy)(JNIEnv* env, jobject, jlong handle) {
  auto* b = reinterpret_cast<Bridge*>(handle);
  if (!b) return;
  hgsim_destroy(b->sim);
  env->DeleteGlobalRef(b->host);
  delete b;
}

HG_JNI(void, begin)(JNIEnv* env, jobject, jlong handle) { hgsim_begin(sim(env, handle)); }

HG_JNI(void, tick)(JNIEnv* env, jobject, jlong handle) { hgsim_tick(sim(env, handle)); }

HG_JNI(void, network)(JNIEnv* env, jobject, jlong handle, jboolean up, jbyteArray detail) {
  hgsim_network(sim(env, handle), up, text(env, detail).c_str());
}

HG_JNI(void, transportOpen)(JNIEnv* env, jobject, jlong handle) { hgsim_transport_open(sim(env, handle)); }

HG_JNI(void, transportText)(JNIEnv* env, jobject, jlong handle, jbyteArray data) {
  std::string t = text(env, data);
  hgsim_transport_text(sim(env, handle), t.data(), t.size());
}

HG_JNI(void, transportBinary)(JNIEnv* env, jobject, jlong handle, jbyteArray data) {
  jsize n = env->GetArrayLength(data);
  std::vector<uint8_t> buf(static_cast<size_t>(n));
  env->GetByteArrayRegion(data, 0, n, reinterpret_cast<jbyte*>(buf.data()));
  hgsim_transport_binary(sim(env, handle), buf.data(), buf.size());
}

HG_JNI(void, transportClosed)(JNIEnv* env, jobject, jlong handle, jbyteArray reason) {
  hgsim_transport_closed(sim(env, handle), text(env, reason).c_str());
}

HG_JNI(void, button)(JNIEnv* env, jobject, jlong handle, jint button, jboolean pressed) {
  hgsim_button(sim(env, handle), button, pressed);
}

HG_JNI(void, touch)(JNIEnv* env, jobject, jlong handle, jboolean touching, jint x, jint y) {
  hgsim_touch(sim(env, handle), touching, x, y);
}

HG_JNI(void, micSamples)(JNIEnv* env, jobject, jlong handle, jshortArray samples, jint count) {
  jshort* pcm = env->GetShortArrayElements(samples, nullptr);
  if (!pcm) return;
  hgsim_mic_samples(sim(env, handle), pcm, static_cast<size_t>(count));
  env->ReleaseShortArrayElements(samples, pcm, JNI_ABORT);
}

HG_JNI(void, submitText)(JNIEnv* env, jobject, jlong handle, jbyteArray value) {
  hgsim_submit_text(sim(env, handle), text(env, value).c_str());
}

HG_JNI(void, setSensor)(JNIEnv* env, jobject, jlong handle, jbyteArray name, jdouble value) {
  hgsim_set_sensor(sim(env, handle), text(env, name).c_str(), value);
}

// Console commands act (`say` sends a message), so each runs exactly once: the
// reply is read into a buffer sized for the longest one, and cut if it is longer.
static constexpr size_t kReplyCap = 16384;

static jbyteArray reply(JNIEnv* env, const std::vector<char>& out, int n) {
  size_t len = n < 0 ? 0 : static_cast<size_t>(n);
  return bytes(env, out.data(), len < out.size() ? len : out.size() - 1);
}

HG_JNI(jbyteArray, console)(JNIEnv* env, jobject, jlong handle, jbyteArray line) {
  std::string l = text(env, line);
  std::vector<char> out(kReplyCap);
  return reply(env, out, hgsim_console(sim(env, handle), l.c_str(), out.data(), out.size()));
}

HG_JNI(jbyteArray, status)(JNIEnv* env, jobject, jlong handle) {
  std::vector<char> out(kReplyCap);
  return reply(env, out, hgsim_status(sim(env, handle), out.data(), out.size()));
}

HG_JNI(jbyteArray, screen)(JNIEnv* env, jobject, jlong handle) { return bytes(env, hgsim_screen(sim(env, handle))); }

// The core's RGB565 framebuffer (native byte order), as a direct buffer over its memory.
HG_JNI(jobject, framebuffer)(JNIEnv* env, jobject, jlong handle) {
  int w = 0, h = 0;
  const uint16_t* fb = hgsim_framebuffer(sim(env, handle), &w, &h);
  if (!fb || !w || !h) return nullptr;
  return env->NewDirectByteBuffer(const_cast<uint16_t*>(fb), static_cast<jlong>(w) * h * 2);
}
