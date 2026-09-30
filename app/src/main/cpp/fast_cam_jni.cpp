#include "fast_cam_bridge.h"
#include "fast_cam_staging.h"

#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <errno.h>
#include <jni.h>
#include <linux/dma-buf.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/system_properties.h>
#include <time.h>

#if defined(__aarch64__)
#include <arm_neon.h>
#endif

#include <atomic>
#include <mutex>
#include <vector>

#define TAG "Strike/FastCam"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace staging;

namespace {

constexpr int kNeedMosaic = 1 << kMosaicView;

// Shark HW map: front, right, rear, left
constexpr uint32_t kSharkHw[4] = {8, 9, 5, 4};

// "0" skips cache maintenance around staging copies, for A/B measurement.
constexpr char kDmaSyncProperty[] = "debug.strike.dmabuf_sync";

std::mutex g_lock;
FastCamClientCtx* g_client = nullptr;
std::atomic<int> g_needs{kNeedMosaic};
std::atomic<bool> g_copying{true};
std::atomic<bool> g_dma_sync{true};
std::atomic<int> g_rate{30};
std::vector<uint8_t> g_full[4];
std::vector<uint8_t> g_quarter[4];
bool g_have_full[4] = {false, false, false, false};
bool g_have_quarter[4] = {false, false, false, false};
uint32_t g_full_seq[4] = {0, 0, 0, 0};
uint32_t g_quarter_seq[4] = {0, 0, 0, 0};
std::atomic<int64_t> g_copy_ns{0};

int16_t g_rv[256];
int16_t g_gu[256];
int16_t g_gv[256];
int16_t g_bu[256];
uint8_t g_clamp[1024];
std::once_flag g_tables_once;

void buildTables() {
    for (int i = 0; i < 256; i++) {
        const int c = i - 128;
        g_rv[i] = static_cast<int16_t>((351 * c) >> 8);
        g_gu[i] = static_cast<int16_t>((86 * c) >> 8);
        g_gv[i] = static_cast<int16_t>((179 * c) >> 8);
        g_bu[i] = static_cast<int16_t>((443 * c) >> 8);
    }
    for (int i = 0; i < 1024; i++) {
        const int v = i - 384;
        g_clamp[i] = static_cast<uint8_t>(v < 0 ? 0 : (v > 255 ? 255 : v));
    }
}

inline uint32_t rgba(int y, int rv, int g, int bu) {
    return 0xFF000000u
            | (static_cast<uint32_t>(g_clamp[y + bu + 384]) << 16)
            | (static_cast<uint32_t>(g_clamp[y - g + 384]) << 8)
            | g_clamp[y + rv + 384];
}

int64_t nowNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

int logicalOf(uint32_t hw) {
    for (int i = 0; i < 4; i++) {
        if (kSharkHw[i] == hw) return i;
    }
    if (hw < 4) return static_cast<int>(hw);
    return -1;
}

bool dmaSync(int fd, uint64_t flags) {
    struct dma_buf_sync sync = {};
    sync.flags = flags;
    int result;
    do {
        result = ioctl(fd, DMA_BUF_IOCTL_SYNC, &sync);
    } while (result < 0 && errno == EINTR);
    return result == 0;
}

void copyFull(const uint8_t* src, uint32_t stride, uint8_t* dst) {
    if (stride == static_cast<uint32_t>(kFullRowBytes)) {
        memcpy(dst, src, kFullBytes);
        return;
    }
    for (int y = 0; y < kFrameH; y++) {
        memcpy(dst + y * kFullRowBytes, src + y * stride, kFullRowBytes);
    }
}

void copyQuarter(const uint8_t* src, uint32_t stride, uint8_t* dst) {
    constexpr int kPairs = kQuarterW / 2;
    for (int y = 0; y < kQuarterH; y++) {
        const uint32_t* in = reinterpret_cast<const uint32_t*>(src + (y * 2) * stride);
        uint32_t* out = reinterpret_cast<uint32_t*>(dst + y * kQuarterRowBytes);
#if defined(__aarch64__)
        for (int x = 0; x < kPairs; x += 4) {
            vst1q_u32(out + x, vld2q_u32(in + x * 2).val[0]);
        }
#else
        for (int x = 0; x < kPairs; x++) out[x] = in[x * 2];
#endif
    }
}

// Byte offset of each output pixel's Y sample in a UYVY source row.
void buildColumnMap(std::vector<int>& map, int src_w, int dst_w) {
    map.resize(static_cast<size_t>(dst_w));
    for (int x = 0; x < dst_w; x++) {
        const int sx = static_cast<int>(static_cast<int64_t>(x) * src_w / dst_w);
        map[static_cast<size_t>(x)] = (sx >> 1) * 4 + 1 + (sx & 1) * 2;
    }
}

void convertRow(const uint8_t* src, uint32_t* dst, int w) {
    int x = 0;
#if defined(__aarch64__)
    // Same fixed-point coefficients as the tables, split so each product fits int16.
    const int16x8_t k128 = vdupq_n_s16(128);
    const uint8x8_t alpha = vdup_n_u8(255);
    for (; x + 16 <= w; x += 16) {
        const uint8x8x4_t p = vld4_u8(src + x * 2);
        const int16x8_t u = vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(p.val[0])), k128);
        const int16x8_t v = vsubq_s16(vreinterpretq_s16_u16(vmovl_u8(p.val[2])), k128);
        const int16x8_t y0 = vreinterpretq_s16_u16(vmovl_u8(p.val[1]));
        const int16x8_t y1 = vreinterpretq_s16_u16(vmovl_u8(p.val[3]));
        const int16x8_t rv = vaddq_s16(v, vshrq_n_s16(vmulq_n_s16(v, 95), 8));
        const int16x8_t g = vaddq_s16(vshrq_n_s16(vmulq_n_s16(u, 86), 8),
                                      vshrq_n_s16(vmulq_n_s16(v, 179), 8));
        const int16x8_t bu = vaddq_s16(u, vshrq_n_s16(vmulq_n_s16(u, 187), 8));
        const uint8x8x2_t r = vzip_u8(vqmovun_s16(vaddq_s16(y0, rv)), vqmovun_s16(vaddq_s16(y1, rv)));
        const uint8x8x2_t gg = vzip_u8(vqmovun_s16(vsubq_s16(y0, g)), vqmovun_s16(vsubq_s16(y1, g)));
        const uint8x8x2_t b = vzip_u8(vqmovun_s16(vaddq_s16(y0, bu)), vqmovun_s16(vaddq_s16(y1, bu)));
        const uint8x8x4_t lo = {{r.val[0], gg.val[0], b.val[0], alpha}};
        const uint8x8x4_t hi = {{r.val[1], gg.val[1], b.val[1], alpha}};
        vst4_u8(reinterpret_cast<uint8_t*>(dst + x), lo);
        vst4_u8(reinterpret_cast<uint8_t*>(dst + x + 8), hi);
    }
#endif
    for (; x + 1 < w; x += 2) {
        const uint8_t* p = src + x * 2;
        const int u = p[0];
        const int v = p[2];
        const int rv = g_rv[v];
        const int g = g_gu[u] + g_gv[v];
        const int bu = g_bu[u];
        dst[x] = rgba(p[1], rv, g, bu);
        dst[x + 1] = rgba(p[3], rv, g, bu);
    }
}

void convertSegment(const uint8_t* src, int src_w, uint32_t* dst, int dst_w,
                    const std::vector<int>& map) {
    if (src == nullptr) {
        for (int x = 0; x < dst_w; x++) dst[x] = 0xFF000000u;
        return;
    }
    if (src_w == dst_w) {
        convertRow(src, dst, dst_w);
        return;
    }
    for (int x = 0; x < dst_w; x++) {
        const int yo = map[static_cast<size_t>(x)];
        const uint8_t* pair = src + (yo & ~3);
        const int u = pair[0];
        const int v = pair[2];
        dst[x] = rgba(src[yo], g_rv[v], g_gu[u] + g_gv[v], g_bu[u]);
    }
}

// Mosaic layout: top-left front, top-right right, bottom-left left, bottom-right rear.
void drawMosaicLocked(uint32_t* dst, int w, int h, int stride_px) {
    const int left_w = w / 2;
    const int right_w = w - left_w;
    const int top_h = h / 2;
    const int bottom_h = h - top_h;
    thread_local std::vector<int> left_map;
    thread_local std::vector<int> right_map;
    buildColumnMap(left_map, kQuarterW, left_w);
    buildColumnMap(right_map, kQuarterW, right_w);
    for (int y = 0; y < h; y++) {
        const bool top = y < top_h;
        const int rows = top ? top_h : bottom_h;
        const int ry = (top ? y : y - top_h) * kQuarterH / rows;
        const uint8_t* left = quarter(top ? 0 : 3);
        const uint8_t* right = quarter(top ? 1 : 2);
        uint32_t* row = dst + y * stride_px;
        convertSegment(left ? left + ry * kQuarterRowBytes : nullptr, kQuarterW, row, left_w, left_map);
        convertSegment(right ? right + ry * kQuarterRowBytes : nullptr, kQuarterW, row + left_w, right_w,
                       right_map);
    }
}

void drawSingleLocked(int cam, uint32_t* dst, int w, int h, int stride_px) {
    thread_local std::vector<int> map;
    buildColumnMap(map, kFrameW, w);
    const uint8_t* base = full(cam);
    for (int y = 0; y < h; y++) {
        const int sy = y * kFrameH / h;
        convertSegment(base ? base + sy * kFullRowBytes : nullptr, kFrameW, dst + y * stride_px, w, map);
    }
}

}  // namespace

namespace staging {

std::mutex& lock() { return g_lock; }

bool viewReady(int view) {
    if (view == kMosaicView) {
        return g_have_quarter[0] || g_have_quarter[1] || g_have_quarter[2] || g_have_quarter[3];
    }
    return view >= 0 && view < 4 && g_have_full[view];
}

const uint8_t* quarter(int cam) { return g_have_quarter[cam] ? g_quarter[cam].data() : nullptr; }

const uint8_t* full(int cam) { return g_have_full[cam] ? g_full[cam].data() : nullptr; }

uint32_t quarterSeq(int cam) { return g_quarter_seq[cam]; }

uint32_t fullSeq(int cam) { return g_full_seq[cam]; }

}  // namespace staging

extern "C" JNIEXPORT jboolean JNICALL
Java_com_strike_camera_FastCamNative_nativeConnect(JNIEnv* env, jclass, jstring path) {
    std::call_once(g_tables_once, buildTables);
    char prop[PROP_VALUE_MAX] = {0};
    __system_property_get(kDmaSyncProperty, prop);
    g_dma_sync.store(strcmp(prop, "0") != 0);
    const char* sock = "@fast_cam.sock";
    const char* chars = nullptr;
    if (path != nullptr) {
        chars = env->GetStringUTFChars(path, nullptr);
        if (chars != nullptr && chars[0] != '\0') sock = chars;
    }
    bool ok = false;
    {
        std::lock_guard<std::mutex> lock(g_lock);
        if (g_client) {
            fast_cam_client_destroy(g_client);
            g_client = nullptr;
        }
        g_client = fast_cam_client_create();
        if (g_client) {
            ok = fast_cam_client_connect(g_client, sock);
            if (!ok) {
                LOGE("connect to %s failed", sock);
                fast_cam_client_destroy(g_client);
                g_client = nullptr;
            } else {
                LOGI("connected to %s (dma-buf sync %s)", sock, g_dma_sync.load() ? "on" : "off");
                fast_cam_client_set_rate(g_client, g_rate.load());
                for (int i = 0; i < 4; i++) {
                    g_have_full[i] = false;
                    g_have_quarter[i] = false;
                }
            }
        }
    }
    if (chars != nullptr) env->ReleaseStringUTFChars(path, chars);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_strike_camera_FastCamNative_nativeDisconnect(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_lock);
    if (g_client) {
        fast_cam_client_destroy(g_client);
        g_client = nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_strike_camera_FastCamNative_nativeIsConnected(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_lock);
    return (g_client && fast_cam_client_is_connected(g_client)) ? JNI_TRUE : JNI_FALSE;
}

// Bits 0-3: single camera views, bit 4: mosaic.
extern "C" JNIEXPORT void JNICALL
Java_com_strike_camera_FastCamNative_nativeSetNeeds(JNIEnv*, jclass, jint mask) {
    const int previous = g_needs.exchange(mask);
    const int added = mask & ~previous;
    if (added == 0) return;
    std::lock_guard<std::mutex> lock(g_lock);
    for (int i = 0; i < 4; i++) {
        if (added & (1 << i)) g_have_full[i] = false;
        if (added & kNeedMosaic) g_have_quarter[i] = false;
    }
}

// Per-camera announce rate; kept across reconnects because a new producer starts at 30.
extern "C" JNIEXPORT void JNICALL
Java_com_strike_camera_FastCamNative_nativeSetRate(JNIEnv*, jclass, jint fps) {
    g_rate.store(fps);
    std::lock_guard<std::mutex> lock(g_lock);
    if (g_client && fast_cam_client_is_connected(g_client)) fast_cam_client_set_rate(g_client, fps);
}

// Off between ticks that paint nothing; staged frames stay valid.
extern "C" JNIEXPORT void JNICALL
Java_com_strike_camera_FastCamNative_nativeSetCopying(JNIEnv*, jclass, jboolean copying) {
    g_copying.store(copying == JNI_TRUE);
}

// Returns the logical camera (0-3) whose frame arrived, or -1.
extern "C" JNIEXPORT jint JNICALL
Java_com_strike_camera_FastCamNative_nativePump(JNIEnv*, jclass, jint timeoutMs) {
    FastCamFrame frame;
    memset(&frame, 0, sizeof(frame));
    frame.dma_buf_fd = -1;
    FastCamClientCtx* client = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_lock);
        client = g_client;
        if (!client || !fast_cam_client_is_connected(client)) return -1;
    }
    if (!fast_cam_client_wait_frame(client, &frame, timeoutMs)) return -1;
    if (!frame.pixels || frame.width != kFrameW || frame.height != kFrameH) return -1;
    const int logical = logicalOf(frame.cam_id);
    if (logical < 0 || logical > 3) return -1;
    if (!g_copying.load()) return logical;
    const uint32_t stride = frame.stride >= static_cast<uint32_t>(kFullRowBytes)
            ? frame.stride : static_cast<uint32_t>(kFullRowBytes);

    // The producer recycles this buffer as soon as it has announced it, so copy now.
    const int needs = g_needs.load();
    const int64_t started = nowNs();
    const bool sync = g_dma_sync.load() && frame.dma_buf_fd >= 0;
    if (sync && !dmaSync(frame.dma_buf_fd, DMA_BUF_SYNC_START | DMA_BUF_SYNC_READ)) {
        LOGW("DMA_BUF_SYNC_START failed (errno %d); copying without cache sync", errno);
        g_dma_sync.store(false);
    }
    {
        std::lock_guard<std::mutex> lock(g_lock);
        if (needs & (1 << logical)) {
            g_full[logical].resize(kFullBytes);
            copyFull(frame.pixels, stride, g_full[logical].data());
            g_have_full[logical] = true;
            g_full_seq[logical]++;
        }
        if (needs & kNeedMosaic) {
            g_quarter[logical].resize(kQuarterBytes);
            copyQuarter(frame.pixels, stride, g_quarter[logical].data());
            g_have_quarter[logical] = true;
            g_quarter_seq[logical]++;
        }
    }
    if (sync && g_dma_sync.load()) dmaSync(frame.dma_buf_fd, DMA_BUF_SYNC_END | DMA_BUF_SYNC_READ);
    g_copy_ns.fetch_add(nowNs() - started);
    return logical;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_strike_camera_FastCamNative_nativeTakeCopyNanos(JNIEnv*, jclass) {
    return g_copy_ns.exchange(0);
}

// CPU path: converts straight into the consumer's buffer at the consumer's size.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_strike_camera_FastCamNative_nativeDrawToWindow(JNIEnv* env, jclass, jobject surface,
                                                        jint view) {
    if (surface == nullptr) return JNI_FALSE;
    std::call_once(g_tables_once, buildTables);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        if (!viewReady(view)) return JNI_FALSE;
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return JNI_FALSE;

    // Keep the consumer's size (MediaCodec input is configured to live/record WxH).
    // Forcing 1920×1300 onto a 1280×864 encoder surface puts the codec into error.
    ANativeWindow_setBuffersGeometry(window, 0, 0, WINDOW_FORMAT_RGBA_8888);
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(window, &buf, nullptr) != 0) {
        ANativeWindow_release(window);
        return JNI_FALSE;
    }
    if (buf.width <= 0 || buf.height <= 0 || buf.bits == nullptr) {
        ANativeWindow_unlockAndPost(window);
        ANativeWindow_release(window);
        return JNI_FALSE;
    }

    auto* dst = static_cast<uint32_t*>(buf.bits);
    {
        std::lock_guard<std::mutex> lock(g_lock);
        if (view == kMosaicView) {
            drawMosaicLocked(dst, buf.width, buf.height, buf.stride);
        } else {
            drawSingleLocked(view, dst, buf.width, buf.height, buf.stride);
        }
    }
    ANativeWindow_unlockAndPost(window);
    ANativeWindow_release(window);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_strike_camera_FastCamNative_nativeFrameWidth(JNIEnv*, jclass) {
    return kFrameW;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_strike_camera_FastCamNative_nativeFrameHeight(JNIEnv*, jclass) {
    return kFrameH;
}
