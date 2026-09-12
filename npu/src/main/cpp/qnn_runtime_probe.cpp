#include <jni.h>
#include <dlfcn.h>
#include <cstdlib>
#include <string>
#ifdef ONETAKE_QNN_AVAILABLE
#include <QnnInterface.h>
#include <HTP/QnnHtpCommon.h>
#endif

extern "C" JNIEXPORT jint JNICALL
Java_com_onetake_npu_QnnRuntimeProbe_probeNative(JNIEnv* env, jobject, jstring path) {
#ifndef ONETAKE_QNN_AVAILABLE
    return 1;
#else
    if (!path) return 2;
    const char* utf = env->GetStringUTFChars(path, nullptr);
    if (!utf) return 2;
    const std::string libraryPath(utf);
    const std::string nativeDirectory = libraryPath.substr(0, libraryPath.find_last_of('/'));
    const char* previousPath = std::getenv("ADSP_LIBRARY_PATH");
    const bool hadPreviousPath = previousPath != nullptr;
    const std::string savedPath = previousPath ? previousPath : "";
    const std::string dspPath = nativeDirectory + ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/dsp" +
        (savedPath.empty() ? "" : ";" + savedPath);
    if (setenv("ADSP_LIBRARY_PATH", dspPath.c_str(), 1) != 0) {
        env->ReleaseStringUTFChars(path, utf);
        return 8;
    }
    void* library = dlopen(utf, RTLD_NOW | RTLD_LOCAL);
    env->ReleaseStringUTFChars(path, utf);
    if (!library) {
        if (hadPreviousPath) setenv("ADSP_LIBRARY_PATH", savedPath.c_str(), 1);
        else unsetenv("ADSP_LIBRARY_PATH");
        return 2;
    }
    // Keep all handles inside this call and release them before unloading QNN.
    const auto providers = reinterpret_cast<decltype(&QnnInterface_getProviders)>(
        dlsym(library, "QnnInterface_getProviders"));
    int result = 3;
    const QnnInterface_t** interfaces = nullptr;
    uint32_t count = 0;
    if (providers && providers(&interfaces, &count) == QNN_SUCCESS && interfaces) {
        result = 4;
        for (uint32_t i = 0; i < count; ++i) {
            const auto* provider = interfaces[i];
            if (!provider || provider->backendId != QNN_BACKEND_ID_HTP ||
                provider->apiVersion.coreApiVersion.major != QNN_API_VERSION_MAJOR ||
                provider->apiVersion.coreApiVersion.minor < QNN_API_VERSION_MINOR) continue;
            const auto& api = provider->QNN_INTERFACE_VER_NAME;
            if (!api.backendCreate || !api.backendFree || !api.deviceCreate || !api.deviceFree) continue;
            Qnn_BackendHandle_t backend = nullptr;
            Qnn_DeviceHandle_t device = nullptr;
            result = 5;
            if (api.backendCreate(nullptr, nullptr, &backend) == QNN_SUCCESS && backend) {
                result = 6;
                if (api.deviceCreate(nullptr, nullptr, &device) == QNN_SUCCESS && device) result = 0;
            }
            if (device && api.deviceFree(device) != QNN_SUCCESS) result = 7;
            if (backend && api.backendFree(backend) != QNN_SUCCESS) result = 7;
            break;
        }
    }
    // A failed release can leave runtime-owned resources alive. Keep its code
    // mapped rather than invalidating those resources by unloading the backend.
    if (result != 7) dlclose(library);
    if (hadPreviousPath) setenv("ADSP_LIBRARY_PATH", savedPath.c_str(), 1);
    else unsetenv("ADSP_LIBRARY_PATH");
    return result;
#endif
}
