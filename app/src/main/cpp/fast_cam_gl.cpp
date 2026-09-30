#include "fast_cam_staging.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <string.h>

#include <mutex>

#define TAG "Strike/FastCamGl"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

using namespace staging;

namespace {

// One RGBA8 texel carries one UYVY pair (U, Y0, V, Y1), so the GPU does the unpack.
constexpr int kQuarterTexels = kQuarterW / 2;
constexpr int kFullTexels = kFrameW / 2;

const char* kVertex = R"(
attribute vec2 aPos;
attribute vec2 aUv;
varying vec2 vUv;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vUv = aUv;
}
)";

// Same coefficients as the CPU tables (351, 86, 179, 443 / 256).
const char* kFragment = R"(
precision highp float;
uniform sampler2D uTex;
uniform vec2 uSize;
varying vec2 vUv;
void main() {
    float px = floor(vUv.x * uSize.x);
    float pair = floor(px * 0.5);
    vec4 t = texture2D(uTex, vec2((pair + 0.5) / uSize.y, vUv.y));
    float y = mod(px, 2.0) < 0.5 ? t.g : t.a;
    float u = t.r - 0.5;
    float v = t.b - 0.5;
    vec3 rgb = vec3(y + 1.371 * v, y - 0.336 * u - 0.699 * v, y + 1.730 * u);
    gl_FragColor = vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
)";

// Texture row 0 is the image's top row; GL's framebuffer origin is bottom-left.
const GLfloat kQuad[] = {
    -1.f, -1.f, 0.f, 1.f,
     1.f, -1.f, 1.f, 1.f,
    -1.f,  1.f, 0.f, 0.f,
     1.f,  1.f, 1.f, 0.f,
};

struct Lane {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    ANativeWindow* window = nullptr;
    GLuint program = 0;
    GLint pos = -1;
    GLint uv = -1;
    GLint size = -1;
    GLuint quarter_tex[4] = {0, 0, 0, 0};
    GLuint full_tex[4] = {0, 0, 0, 0};
    uint32_t quarter_seq[4] = {0, 0, 0, 0};
    uint32_t full_seq[4] = {0, 0, 0, 0};
    bool quarter_ok[4] = {false, false, false, false};
    bool full_ok[4] = {false, false, false, false};
    int64_t last_pts = 0;
    PFNEGLPRESENTATIONTIMEANDROIDPROC present = nullptr;
};

GLuint compile(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {0};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("shader compile failed: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

GLuint link() {
    GLuint vs = compile(GL_VERTEX_SHADER, kVertex);
    GLuint fs = compile(GL_FRAGMENT_SHADER, kFragment);
    if (!vs || !fs) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return 0;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (!ok) {
        LOGE("program link failed");
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

void destroy(Lane* lane) {
    if (lane->display != EGL_NO_DISPLAY) {
        if (lane->context != EGL_NO_CONTEXT) {
            eglMakeCurrent(lane->display, lane->surface, lane->surface, lane->context);
            glDeleteTextures(4, lane->quarter_tex);
            glDeleteTextures(4, lane->full_tex);
            if (lane->program) glDeleteProgram(lane->program);
        }
        eglMakeCurrent(lane->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (lane->surface != EGL_NO_SURFACE) eglDestroySurface(lane->display, lane->surface);
        if (lane->context != EGL_NO_CONTEXT) eglDestroyContext(lane->display, lane->context);
        // No eglTerminate: the display is shared with every other lane in the process.
    }
    if (lane->window) ANativeWindow_release(lane->window);
    eglReleaseThread();
    delete lane;
}

Lane* create(JNIEnv* env, jobject surface) {
    auto* lane = new Lane();
    lane->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (lane->display == EGL_NO_DISPLAY || !eglInitialize(lane->display, nullptr, nullptr)) {
        LOGE("eglInitialize failed: 0x%x", eglGetError());
        lane->display = EGL_NO_DISPLAY;
        destroy(lane);
        return nullptr;
    }
    const EGLint config_attrs[] = {
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RECORDABLE_ANDROID, EGL_TRUE,
        EGL_NONE,
    };
    EGLConfig config = nullptr;
    EGLint count = 0;
    if (!eglChooseConfig(lane->display, config_attrs, &config, 1, &count) || count < 1) {
        LOGE("no recordable RGBA8888 config: 0x%x", eglGetError());
        destroy(lane);
        return nullptr;
    }
    const EGLint context_attrs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    lane->context = eglCreateContext(lane->display, config, EGL_NO_CONTEXT, context_attrs);
    if (lane->context == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: 0x%x", eglGetError());
        destroy(lane);
        return nullptr;
    }
    lane->window = ANativeWindow_fromSurface(env, surface);
    if (!lane->window) {
        destroy(lane);
        return nullptr;
    }
    lane->surface = eglCreateWindowSurface(lane->display, config, lane->window, nullptr);
    if (lane->surface == EGL_NO_SURFACE) {
        LOGE("eglCreateWindowSurface failed: 0x%x", eglGetError());
        destroy(lane);
        return nullptr;
    }
    if (!eglMakeCurrent(lane->display, lane->surface, lane->surface, lane->context)) {
        LOGE("eglMakeCurrent failed: 0x%x", eglGetError());
        destroy(lane);
        return nullptr;
    }
    static std::once_flag probed;
    std::call_once(probed, [lane] {
        const char* egl = eglQueryString(lane->display, EGL_EXTENSIONS);
        const char* gl = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
        auto has = [](const char* list, const char* name) {
            return list != nullptr && strstr(list, name) != nullptr;
        };
        LOGW("%s; dma_buf_import=%d modifiers=%d image_external=%d",
             reinterpret_cast<const char*>(glGetString(GL_RENDERER)),
             has(egl, "EGL_EXT_image_dma_buf_import"),
             has(egl, "EGL_EXT_image_dma_buf_import_modifiers"),
             has(gl, "GL_OES_EGL_image_external"));
    });
    lane->program = link();
    if (!lane->program) {
        destroy(lane);
        return nullptr;
    }
    lane->pos = glGetAttribLocation(lane->program, "aPos");
    lane->uv = glGetAttribLocation(lane->program, "aUv");
    lane->size = glGetUniformLocation(lane->program, "uSize");
    lane->present = reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(
            eglGetProcAddress("eglPresentationTimeANDROID"));
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    return lane;
}

GLuint newTexture(int width, int height) {
    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    return tex;
}

// Re-uploads a camera only when its staged copy has changed since this lane last read it.
void upload(GLuint& tex, uint32_t& seen, bool& ok, const uint8_t* pixels, uint32_t seq,
            int texels, int rows) {
    if (!pixels) {
        ok = false;
        return;
    }
    if (!tex) tex = newTexture(texels, rows);
    if (ok && seen == seq) return;
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, texels, rows, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    seen = seq;
    ok = true;
}

void drawQuad(Lane* lane, GLuint tex, int x, int y, int w, int h, int src_w, int texels) {
    glViewport(x, y, w, h);
    glBindTexture(GL_TEXTURE_2D, tex);
    glUniform2f(lane->size, static_cast<GLfloat>(src_w), static_cast<GLfloat>(texels));
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

bool draw(Lane* lane, int view, int64_t pts_ns) {
    EGLint width = 0;
    EGLint height = 0;
    eglQuerySurface(lane->display, lane->surface, EGL_WIDTH, &width);
    eglQuerySurface(lane->display, lane->surface, EGL_HEIGHT, &height);
    if (width <= 0 || height <= 0) return false;

    {
        std::lock_guard<std::mutex> guard(staging::lock());
        if (!viewReady(view)) return false;
        if (view == kMosaicView) {
            for (int cam = 0; cam < 4; cam++) {
                upload(lane->quarter_tex[cam], lane->quarter_seq[cam], lane->quarter_ok[cam],
                       quarter(cam), quarterSeq(cam), kQuarterTexels, kQuarterH);
            }
        } else if (view >= 0 && view < 4) {
            upload(lane->full_tex[view], lane->full_seq[view], lane->full_ok[view],
                   full(view), fullSeq(view), kFullTexels, kFrameH);
        } else {
            return false;
        }
    }

    glViewport(0, 0, width, height);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    glUseProgram(lane->program);
    glActiveTexture(GL_TEXTURE0);
    glVertexAttribPointer(lane->pos, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), kQuad);
    glVertexAttribPointer(lane->uv, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), kQuad + 2);
    glEnableVertexAttribArray(lane->pos);
    glEnableVertexAttribArray(lane->uv);

    if (view == kMosaicView) {
        const int left_w = width / 2;
        const int right_w = width - left_w;
        const int top_h = height / 2;
        const int bottom_h = height - top_h;
        // Layout: top-left front, top-right right, bottom-left left, bottom-right rear.
        const int cams[4] = {0, 1, 3, 2};
        const int xs[4] = {0, left_w, 0, left_w};
        const int ws[4] = {left_w, right_w, left_w, right_w};
        const int ys[4] = {bottom_h, bottom_h, 0, 0};
        const int hs[4] = {top_h, top_h, bottom_h, bottom_h};
        for (int i = 0; i < 4; i++) {
            const int cam = cams[i];
            if (!lane->quarter_ok[cam]) continue;
            drawQuad(lane, lane->quarter_tex[cam], xs[i], ys[i], ws[i], hs[i], kQuarterW, kQuarterTexels);
        }
    } else {
        drawQuad(lane, lane->full_tex[view], 0, 0, width, height, kFrameW, kFullTexels);
    }

    // Encoders see the frame's arrival time, not when this lane got to it.
    int64_t pts = pts_ns;
    if (pts <= lane->last_pts) pts = lane->last_pts + 1000;
    lane->last_pts = pts;
    if (lane->present) lane->present(lane->display, lane->surface, pts);
    if (!eglSwapBuffers(lane->display, lane->surface)) {
        LOGE("eglSwapBuffers failed: 0x%x", eglGetError());
        return false;
    }
    return true;
}

}  // namespace

// All three calls must come from the same thread; the context stays current on it.
extern "C" JNIEXPORT jlong JNICALL
Java_com_strike_camera_FastCamNative_nativeGlCreate(JNIEnv* env, jclass, jobject surface) {
    if (surface == nullptr) return 0;
    return reinterpret_cast<jlong>(create(env, surface));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_strike_camera_FastCamNative_nativeGlDraw(JNIEnv*, jclass, jlong handle, jint view,
                                                  jlong ptsNs) {
    if (handle == 0) return JNI_FALSE;
    return draw(reinterpret_cast<Lane*>(handle), view, ptsNs) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_strike_camera_FastCamNative_nativeGlDestroy(JNIEnv*, jclass, jlong handle) {
    if (handle == 0) return;
    destroy(reinterpret_cast<Lane*>(handle));
}
