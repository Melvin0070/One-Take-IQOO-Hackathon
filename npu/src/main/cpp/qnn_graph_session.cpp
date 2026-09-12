#include <jni.h>

#include <dlfcn.h>

#include <cerrno>
#include <climits>
#include <cstdint>
#include <cstdlib>
#include <exception>
#include <fstream>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <utility>
#include <vector>

#ifdef ONETAKE_QNN_AVAILABLE
#include <QnnInterface.h>
#include <HTP/QnnHtpCommon.h>
#include <System/QnnSystemInterface.h>
#endif

namespace {

constexpr uint64_t kMaxContextBinaryBytes = 1ULL << 31;
constexpr uint32_t kMaxTensorRank = 64;

void throwGraphException(JNIEnv* env, const char* message) noexcept {
    if (!env || env->ExceptionCheck()) return;
    jclass exceptionClass = env->FindClass("com/onetake/npu/QnnGraphException");
    if (!exceptionClass) {
        env->ExceptionClear();
        exceptionClass = env->FindClass("java/lang/IllegalStateException");
    }
    if (exceptionClass) {
        env->ThrowNew(exceptionClass, message);
        env->DeleteLocalRef(exceptionClass);
    }
}

void throwGraphException(JNIEnv* env, const std::string& message) noexcept {
    throwGraphException(env, message.c_str());
}

bool readString(JNIEnv* env, jstring value, std::string* result, bool allowNull) {
    if (!value) {
        if (allowNull) {
            result->clear();
            return true;
        }
        throwGraphException(env, "QNN graph runtime received a null string");
        return false;
    }
    const char* utf = env->GetStringUTFChars(value, nullptr);
    if (!utf) {
        return false;
    }
    try {
        result->assign(utf);
    } catch (...) {
        env->ReleaseStringUTFChars(value, utf);
        throw;
    }
    env->ReleaseStringUTFChars(value, utf);
    return true;
}

#ifdef ONETAKE_QNN_AVAILABLE

std::string qnnError(const char* operation, Qnn_ErrorHandle_t code) {
    return std::string(operation) + " failed (QNN error " +
        std::to_string(static_cast<unsigned long long>(code)) + ")";
}

std::string joinPath(const std::string& directory, const char* fileName) {
    if (directory.empty()) return fileName;
    if (directory.back() == '/') return directory + fileName;
    return directory + "/" + fileName;
}

class ScopedAdspLibraryPath {
public:
    explicit ScopedAdspLibraryPath(const std::string& nativeDirectory)
        : lock_(adspMutex()) {
        const char* previous = std::getenv("ADSP_LIBRARY_PATH");
        hadPrevious_ = previous != nullptr;
        previousValue_ = previous ? previous : "";
        std::string path = nativeDirectory +
            ";/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/dsp";
        if (hadPrevious_ && !previousValue_.empty()) {
            path += ";" + previousValue_;
        }
        valid_ = setenv("ADSP_LIBRARY_PATH", path.c_str(), 1) == 0;
    }

    ~ScopedAdspLibraryPath() {
        if (hadPrevious_) {
            setenv("ADSP_LIBRARY_PATH", previousValue_.c_str(), 1);
        } else {
            unsetenv("ADSP_LIBRARY_PATH");
        }
    }

    bool valid() const { return valid_; }

private:
    static std::mutex& adspMutex() {
        static std::mutex mutex;
        return mutex;
    }

    std::unique_lock<std::mutex> lock_;
    bool hadPrevious_ = false;
    bool valid_ = false;
    std::string previousValue_;
};

struct Runtime {
    std::mutex mutex;
    void* htpLibrary = nullptr;
    void* systemLibrary = nullptr;
    const QnnInterface_t* qnn = nullptr;
    const QnnSystemInterface_t* system = nullptr;
};

Runtime& runtime() {
    static Runtime value;
    return value;
}

std::string dynamicLoadingError(const char* path) {
    const char* error = dlerror();
    return std::string("Unable to load ") + path +
        (error ? std::string(": ") + error : std::string());
}

bool loadRuntime(const std::string& nativeDirectory, std::string* error) {
    Runtime& state = runtime();
    std::lock_guard<std::mutex> lock(state.mutex);

    if (!state.htpLibrary) {
        const std::string path = joinPath(nativeDirectory, "libQnnHtp.so");
        state.htpLibrary = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!state.htpLibrary) {
            *error = dynamicLoadingError(path.c_str());
            return false;
        }
    }
    if (!state.qnn) {
        const auto providers = reinterpret_cast<decltype(&QnnInterface_getProviders)>(
            dlsym(state.htpLibrary, "QnnInterface_getProviders"));
        if (!providers) {
            *error = "QNN HTP interface provider is unavailable";
            return false;
        }
        const QnnInterface_t** providerList = nullptr;
        uint32_t providerCount = 0;
        if (providers(&providerList, &providerCount) != QNN_SUCCESS || !providerList) {
            *error = "QNN HTP interface provider enumeration failed";
            return false;
        }
        for (uint32_t index = 0; index < providerCount; ++index) {
            const QnnInterface_t* candidate = providerList[index];
            if (!candidate || candidate->backendId != QNN_BACKEND_ID_HTP ||
                candidate->apiVersion.coreApiVersion.major != QNN_API_VERSION_MAJOR ||
                candidate->apiVersion.coreApiVersion.minor < QNN_API_VERSION_MINOR) {
                continue;
            }
            const auto& api = candidate->QNN_INTERFACE_VER_NAME;
            if (!api.backendCreate || !api.backendFree || !api.deviceCreate ||
                !api.deviceFree || !api.contextCreateFromBinary || !api.contextFree ||
                !api.graphRetrieve || !api.graphExecute) {
                continue;
            }
            state.qnn = candidate;
            break;
        }
        if (!state.qnn) {
            *error = "QNN HTP provider has no compatible graph execution interface";
            return false;
        }
    }

    if (!state.systemLibrary) {
        const std::string path = joinPath(nativeDirectory, "libQnnSystem.so");
        state.systemLibrary = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!state.systemLibrary) {
            *error = dynamicLoadingError(path.c_str());
            return false;
        }
    }
    if (!state.system) {
        const auto providers = reinterpret_cast<decltype(&QnnSystemInterface_getProviders)>(
            dlsym(state.systemLibrary, "QnnSystemInterface_getProviders"));
        if (!providers) {
            *error = "QNN System interface provider is unavailable";
            return false;
        }
        const QnnSystemInterface_t** providerList = nullptr;
        uint32_t providerCount = 0;
        if (providers(&providerList, &providerCount) != QNN_SUCCESS || !providerList) {
            *error = "QNN System interface provider enumeration failed";
            return false;
        }
        for (uint32_t index = 0; index < providerCount; ++index) {
            const QnnSystemInterface_t* candidate = providerList[index];
            if (!candidate ||
                candidate->systemApiVersion.major != QNN_SYSTEM_API_VERSION_MAJOR ||
                candidate->systemApiVersion.minor < QNN_SYSTEM_API_VERSION_MINOR) {
                continue;
            }
            const auto& api = candidate->QNN_SYSTEM_INTERFACE_VER_NAME;
            if (!api.systemContextCreate || !api.systemContextFree ||
                (!api.systemContextGetBinaryInfo && !api.systemContextGetMetaData)) {
                continue;
            }
            state.system = candidate;
            break;
        }
        if (!state.system) {
            *error = "QNN System provider has no compatible metadata interface";
            return false;
        }
    }
    return true;
}

struct TensorSpecNative {
    uint32_t id = 0;
    Qnn_TensorVersion_t version = QNN_TENSOR_VERSION_UNDEFINED;
    Qnn_TensorType_t type = QNN_TENSOR_TYPE_UNDEFINED;
    Qnn_TensorDataFormat_t dataFormat = QNN_TENSOR_DATA_FORMAT_DENSE;
    Qnn_DataType_t dataType = QNN_DATATYPE_UNDEFINED;
    std::string name;
    std::vector<uint32_t> dimensions;
    uint32_t byteCount = 0;
};

struct GraphView {
    const char* name = nullptr;
    uint32_t inputCount = 0;
    const Qnn_Tensor_t* inputs = nullptr;
    uint32_t outputCount = 0;
    const Qnn_Tensor_t* outputs = nullptr;
};

struct GraphSession;
void releaseSession(GraphSession* session, std::string* error);

struct SystemContextGuard {
    const QnnSystemInterface_t* provider = nullptr;
    QnnSystemContext_Handle_t handle = nullptr;

    ~SystemContextGuard() {
        if (provider && handle) {
            provider->QNN_SYSTEM_INTERFACE_VER_NAME.systemContextFree(handle);
        }
    }

    bool close(std::string* error) {
        if (!provider || !handle) return true;
        QnnSystemContext_Handle_t systemContext = handle;
        handle = nullptr;
        const Qnn_ErrorHandle_t result =
            provider->QNN_SYSTEM_INTERFACE_VER_NAME.systemContextFree(systemContext);
        if (result != QNN_SUCCESS) {
            *error = qnnError("systemContextFree", result);
            return false;
        }
        return true;
    }
};

bool getGraphView(const QnnSystemContext_GraphInfo_t& graph, GraphView* view,
                  std::string* error) {
    switch (graph.version) {
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_1:
            view->name = graph.graphInfoV1.graphName;
            view->inputCount = graph.graphInfoV1.numGraphInputs;
            view->inputs = graph.graphInfoV1.graphInputs;
            view->outputCount = graph.graphInfoV1.numGraphOutputs;
            view->outputs = graph.graphInfoV1.graphOutputs;
            return true;
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_2:
            view->name = graph.graphInfoV2.graphName;
            view->inputCount = graph.graphInfoV2.numGraphInputs;
            view->inputs = graph.graphInfoV2.graphInputs;
            view->outputCount = graph.graphInfoV2.numGraphOutputs;
            view->outputs = graph.graphInfoV2.graphOutputs;
            return true;
        case QNN_SYSTEM_CONTEXT_GRAPH_INFO_VERSION_3:
            view->name = graph.graphInfoV3.graphName;
            view->inputCount = graph.graphInfoV3.numGraphInputs;
            view->inputs = graph.graphInfoV3.graphInputs;
            view->outputCount = graph.graphInfoV3.numGraphOutputs;
            view->outputs = graph.graphInfoV3.graphOutputs;
            return true;
        default:
            *error = "QNN context binary uses an unsupported graph metadata version";
            return false;
    }
}

bool getBinaryView(const QnnSystemContext_BinaryInfo_t& binary, uint32_t* backendId,
                  uint32_t* graphCount, const QnnSystemContext_GraphInfo_t** graphs,
                  std::string* error) {
    switch (binary.version) {
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_1:
            *backendId = binary.contextBinaryInfoV1.backendId;
            *graphCount = binary.contextBinaryInfoV1.numGraphs;
            *graphs = binary.contextBinaryInfoV1.graphs;
            return true;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_2:
            *backendId = binary.contextBinaryInfoV2.backendId;
            *graphCount = binary.contextBinaryInfoV2.numGraphs;
            *graphs = binary.contextBinaryInfoV2.graphs;
            return true;
        case QNN_SYSTEM_CONTEXT_BINARY_INFO_VERSION_3:
            *backendId = binary.contextBinaryInfoV3.backendId;
            *graphCount = binary.contextBinaryInfoV3.numGraphs;
            *graphs = binary.contextBinaryInfoV3.graphs;
            return true;
        default:
            *error = "QNN context binary uses an unsupported binary metadata version";
            return false;
    }
}

bool checkedByteCount(Qnn_DataType_t dataType, const std::vector<uint32_t>& dimensions,
                      uint32_t* byteCount, std::string* error) {
    uint64_t elements = 1;
    uint32_t bits = 0;
    switch (dataType) {
        case QNN_DATATYPE_FLOAT_16:
            bits = 16;
            break;
        case QNN_DATATYPE_FLOAT_32:
        case QNN_DATATYPE_INT_32:
            bits = 32;
            break;
        default:
            *error = "QNN graph uses an unsupported raw tensor data type";
            return false;
    }
    for (uint32_t dimension : dimensions) {
        if (dimension == 0 || elements > std::numeric_limits<uint64_t>::max() / dimension) {
            *error = "QNN graph tensor dimensions overflow";
            return false;
        }
        elements *= dimension;
    }
    if (elements > std::numeric_limits<uint64_t>::max() / bits) {
        *error = "QNN graph tensor byte count overflows";
        return false;
    }
    const uint64_t bitsTotal = elements * bits;
    if (bitsTotal > std::numeric_limits<uint64_t>::max() - 7) {
        *error = "QNN graph tensor byte count overflows";
        return false;
    }
    const uint64_t bytes = (bitsTotal + 7) / 8;
    if (bytes == 0 || bytes > static_cast<uint64_t>(INT_MAX)) {
        *error = "QNN graph tensor is too large for a Java raw byte buffer";
        return false;
    }
    *byteCount = static_cast<uint32_t>(bytes);
    return true;
}

bool copyTensor(const Qnn_Tensor_t& tensor, TensorSpecNative* result, std::string* error) {
    result->version = tensor.version;
    const char* name = nullptr;
    uint32_t rank = 0;
    uint32_t* dimensions = nullptr;
    Qnn_QuantizationEncoding_t quantizationEncoding =
        QNN_QUANTIZATION_ENCODING_UNDEFINED;
    if (tensor.version == QNN_TENSOR_VERSION_1) {
        *error = "QNN graph requires V2 tensor metadata with explicit static dimensions";
        return false;
    } else if (tensor.version == QNN_TENSOR_VERSION_2) {
        result->id = tensor.v2.id;
        result->type = tensor.v2.type;
        result->dataFormat = tensor.v2.dataFormat;
        result->dataType = tensor.v2.dataType;
        quantizationEncoding = tensor.v2.quantizeParams.quantizationEncoding;
        name = tensor.v2.name;
        rank = tensor.v2.rank;
        dimensions = tensor.v2.dimensions;
        if (rank > kMaxTensorRank) {
            *error = "QNN graph tensor rank is too large";
            return false;
        }
        if (tensor.v2.isDynamicDimensions) {
            for (uint32_t index = 0; index < rank; ++index) {
                if (tensor.v2.isDynamicDimensions[index] != 0) {
                    *error = "QNN graph dynamic tensor dimensions are not supported";
                    return false;
                }
            }
        }
    } else {
        *error = "QNN graph uses an unsupported tensor metadata version";
        return false;
    }
    if (!name || *name == '\0') {
        *error = "QNN graph tensor has no name";
        return false;
    }
    if (rank > kMaxTensorRank || (rank > 0 && !dimensions)) {
        *error = "QNN graph tensor rank or dimensions are invalid";
        return false;
    }
    if (result->dataFormat != QNN_TENSOR_DATA_FORMAT_DENSE) {
        *error = "QNN graph uses a non-dense tensor data format";
        return false;
    }
    if (result->dataType != QNN_DATATYPE_FLOAT_16 &&
        result->dataType != QNN_DATATYPE_FLOAT_32 &&
        result->dataType != QNN_DATATYPE_INT_32) {
        *error = "QNN graph raw execution supports only FP16, FP32, and INT32 tensors";
        return false;
    }
    if (quantizationEncoding != QNN_QUANTIZATION_ENCODING_UNDEFINED) {
        *error = "QNN graph quantized tensor metadata is not supported";
        return false;
    }
    result->name = name;
    if (rank > 0) {
        result->dimensions.assign(dimensions, dimensions + rank);
    }
    if (!checkedByteCount(result->dataType, result->dimensions, &result->byteCount, error)) {
        return false;
    }
    return true;
}

bool readBinary(const std::string& path, std::vector<uint8_t>* binary, std::string* error) {
    std::ifstream stream(path, std::ios::binary | std::ios::ate);
    if (!stream) {
        *error = "Unable to open QNN context binary";
        return false;
    }
    const std::streamoff length = stream.tellg();
    if (length <= 0 || static_cast<uint64_t>(length) > kMaxContextBinaryBytes) {
        *error = "QNN context binary has an invalid size";
        return false;
    }
    binary->resize(static_cast<size_t>(length));
    stream.seekg(0, std::ios::beg);
    if (!stream.read(reinterpret_cast<char*>(binary->data()), length)) {
        *error = "Unable to read QNN context binary";
        return false;
    }
    return true;
}

struct GraphSession {
    ~GraphSession() {
        releaseSession(this, nullptr);
    }

    const QnnInterface_t* qnn = nullptr;
    std::vector<uint8_t> binary;
    std::string graphName;
    std::vector<TensorSpecNative> inputs;
    std::vector<TensorSpecNative> outputs;
    Qnn_BackendHandle_t backend = nullptr;
    Qnn_DeviceHandle_t device = nullptr;
    Qnn_ContextHandle_t context = nullptr;
    Qnn_GraphHandle_t graph = nullptr;
    std::mutex mutex;
    bool closed = false;
};

void releaseSession(GraphSession* session, std::string* error) {
    if (!session || !session->qnn) return;
    const auto& api = session->qnn->QNN_INTERFACE_VER_NAME;
    const char* firstOperation = nullptr;
    Qnn_ErrorHandle_t firstError = QNN_SUCCESS;
    auto record = [&firstOperation, &firstError](const char* operation, Qnn_ErrorHandle_t code) {
        if (code != QNN_SUCCESS && firstOperation == nullptr) {
            firstOperation = operation;
            firstError = code;
        }
    };
    if (session->context) {
        Qnn_ContextHandle_t context = session->context;
        session->context = nullptr;
        record("contextFree", api.contextFree(context, nullptr));
    }
    if (session->device) {
        Qnn_DeviceHandle_t device = session->device;
        session->device = nullptr;
        record("deviceFree", api.deviceFree(device));
    }
    if (session->backend) {
        Qnn_BackendHandle_t backend = session->backend;
        session->backend = nullptr;
        record("backendFree", api.backendFree(backend));
    }
    session->graph = nullptr;
    if (error && firstOperation && error->empty()) {
        *error = qnnError(firstOperation, firstError);
    }
}

bool inspectMetadata(const QnnSystemInterface_t* systemInterface,
                     const std::vector<uint8_t>& binary, const std::string& requestedName,
                     GraphSession* session, std::string* error) {
    const auto& api = systemInterface->QNN_SYSTEM_INTERFACE_VER_NAME;
    QnnSystemContext_Handle_t systemContext = nullptr;
    Qnn_ErrorHandle_t result = api.systemContextCreate(&systemContext);
    if (result != QNN_SUCCESS || !systemContext) {
        *error = qnnError("systemContextCreate", result);
        return false;
    }
    SystemContextGuard systemContextGuard{systemInterface, systemContext};
    const QnnSystemContext_BinaryInfo_t* binaryInfo = nullptr;
    Qnn_ContextBinarySize_t binaryInfoSize = 0;
    if (api.systemContextGetBinaryInfo) {
        result = api.systemContextGetBinaryInfo(
            systemContext,
            const_cast<uint8_t*>(binary.data()),
            static_cast<uint64_t>(binary.size()),
            &binaryInfo,
            &binaryInfoSize);
    } else {
        result = api.systemContextGetMetaData(
            systemContext,
            binary.data(),
            static_cast<uint64_t>(binary.size()),
            &binaryInfo);
    }
    if (result != QNN_SUCCESS || !binaryInfo) {
        std::string operation = api.systemContextGetBinaryInfo
            ? "systemContextGetBinaryInfo"
            : "systemContextGetMetaData";
        *error = qnnError(operation.c_str(), result);
        return false;
    }

    uint32_t backendId = 0;
    uint32_t graphCount = 0;
    const QnnSystemContext_GraphInfo_t* graphs = nullptr;
    bool valid = getBinaryView(*binaryInfo, &backendId, &graphCount, &graphs, error);
    if (valid && backendId != QNN_BACKEND_ID_HTP) {
        *error = "QNN context binary is not an HTP backend binary";
        valid = false;
    }
    if (valid && (graphCount == 0 || !graphs)) {
        *error = "QNN context binary contains no graph metadata";
        valid = false;
    }
    const QnnSystemContext_GraphInfo_t* selected = nullptr;
    GraphView selectedView;
    if (valid) {
        for (uint32_t index = 0; index < graphCount; ++index) {
            GraphView view;
            if (!getGraphView(graphs[index], &view, error)) {
                valid = false;
                break;
            }
            if (!view.name) {
                *error = "QNN context binary contains a graph without a name";
                valid = false;
                break;
            }
            if ((!requestedName.empty() && requestedName == view.name) ||
                (requestedName.empty() && graphCount == 1)) {
                selected = &graphs[index];
                selectedView = view;
            }
        }
    }
    if (valid && !selected) {
        *error = requestedName.empty()
            ? "QNN context binary contains multiple graphs; graphName is required"
            : "Requested QNN graph name was not found in the context binary";
        valid = false;
    }
    if (valid) {
        session->graphName = selectedView.name;
        if ((selectedView.inputCount > 0 && !selectedView.inputs) ||
            (selectedView.outputCount > 0 && !selectedView.outputs)) {
            *error = "QNN graph metadata has a missing tensor list";
            valid = false;
        } else if (selectedView.inputCount > static_cast<uint32_t>(INT_MAX) ||
                   selectedView.outputCount > static_cast<uint32_t>(INT_MAX)) {
            *error = "QNN graph metadata contains too many tensors";
            valid = false;
        }
    }
    if (valid) {
        session->inputs.reserve(selectedView.inputCount);
        session->outputs.reserve(selectedView.outputCount);
        for (uint32_t index = 0; index < selectedView.inputCount; ++index) {
            TensorSpecNative tensor;
            if (!copyTensor(selectedView.inputs[index], &tensor, error)) {
                valid = false;
                break;
            }
            session->inputs.push_back(std::move(tensor));
        }
    }
    if (valid) {
        for (uint32_t index = 0; index < selectedView.outputCount; ++index) {
            TensorSpecNative tensor;
            if (!copyTensor(selectedView.outputs[index], &tensor, error)) {
                valid = false;
                break;
            }
            session->outputs.push_back(std::move(tensor));
        }
    }
    if (valid && !systemContextGuard.close(error)) {
        valid = false;
    }
    return valid;
}

bool openSession(const std::string& nativeDirectory, const std::string& binaryPath,
                 const std::string& requestedName, GraphSession* session,
                 std::string* error) {
    ScopedAdspLibraryPath adspPath(nativeDirectory);
    if (!adspPath.valid()) {
        *error = "Unable to configure ADSP_LIBRARY_PATH for QNN HTP";
        return false;
    }
    if (!readBinary(binaryPath, &session->binary, error)) return false;
    if (!loadRuntime(nativeDirectory, error)) return false;
    Runtime& state = runtime();
    session->qnn = state.qnn;
    if (!inspectMetadata(state.system, session->binary, requestedName, session, error)) {
        return false;
    }
    const auto& api = session->qnn->QNN_INTERFACE_VER_NAME;
    Qnn_ErrorHandle_t result = api.backendCreate(nullptr, nullptr, &session->backend);
    if (result != QNN_SUCCESS || !session->backend) {
        *error = qnnError("backendCreate", result);
        releaseSession(session, error);
        return false;
    }
    result = api.deviceCreate(nullptr, nullptr, &session->device);
    if (result != QNN_SUCCESS || !session->device) {
        *error = qnnError("deviceCreate", result);
        releaseSession(session, error);
        return false;
    }
    result = api.contextCreateFromBinary(
        session->backend,
        session->device,
        nullptr,
        session->binary.data(),
        static_cast<Qnn_ContextBinarySize_t>(session->binary.size()),
        &session->context,
        nullptr);
    if (result != QNN_SUCCESS || !session->context) {
        *error = qnnError("contextCreateFromBinary", result);
        releaseSession(session, error);
        return false;
    }
    result = api.graphRetrieve(session->context, session->graphName.c_str(), &session->graph);
    if (result != QNN_SUCCESS || !session->graph) {
        *error = qnnError("graphRetrieve", result);
        releaseSession(session, error);
        return false;
    }
    return true;
}

Qnn_Tensor_t makeTensor(const TensorSpecNative& spec, void* data, uint32_t dataSize) {
    Qnn_Tensor_t tensor{};
    if (spec.version == QNN_TENSOR_VERSION_2) {
        tensor.version = QNN_TENSOR_VERSION_2;
        tensor.v2.id = spec.id;
        tensor.v2.name = spec.name.c_str();
        tensor.v2.type = spec.type;
        tensor.v2.dataFormat = spec.dataFormat;
        tensor.v2.dataType = spec.dataType;
        tensor.v2.quantizeParams = QNN_QUANTIZE_PARAMS_INIT;
        tensor.v2.rank = static_cast<uint32_t>(spec.dimensions.size());
        tensor.v2.dimensions = spec.dimensions.empty()
            ? nullptr
            : const_cast<uint32_t*>(spec.dimensions.data());
        tensor.v2.memType = QNN_TENSORMEMTYPE_RAW;
        tensor.v2.clientBuf.data = data;
        tensor.v2.clientBuf.dataSize = dataSize;
        tensor.v2.isDynamicDimensions = nullptr;
        tensor.v2.sparseParams = QNN_SPARSE_PARAMS_INIT;
        tensor.v2.isProduced = 0;
    } else {
        tensor.version = QNN_TENSOR_VERSION_1;
        tensor.v1.id = spec.id;
        tensor.v1.name = spec.name.c_str();
        tensor.v1.type = spec.type;
        tensor.v1.dataFormat = spec.dataFormat;
        tensor.v1.dataType = spec.dataType;
        tensor.v1.quantizeParams = QNN_QUANTIZE_PARAMS_INIT;
        tensor.v1.rank = static_cast<uint32_t>(spec.dimensions.size());
        tensor.v1.dimensions = spec.dimensions.empty()
            ? nullptr
            : const_cast<uint32_t*>(spec.dimensions.data());
        tensor.v1.memType = QNN_TENSORMEMTYPE_RAW;
        tensor.v1.clientBuf.data = data;
        tensor.v1.clientBuf.dataSize = dataSize;
    }
    return tensor;
}

bool validHandle(jlong value) {
    return value != 0;
}

GraphSession* sessionFrom(jlong value) {
    return reinterpret_cast<GraphSession*>(static_cast<uintptr_t>(value));
}

jobject makeTensorMetadata(JNIEnv* env, const TensorSpecNative& tensor) {
    jclass tensorClass = env->FindClass("com/onetake/npu/QnnNativeTensorSpec");
    if (!tensorClass) return nullptr;
    const jmethodID constructor = env->GetMethodID(
        tensorClass, "<init>", "(Ljava/lang/String;I[JI)V");
    if (!constructor) {
        env->DeleteLocalRef(tensorClass);
        return nullptr;
    }
    jstring name = env->NewStringUTF(tensor.name.c_str());
    jlongArray dimensions = env->NewLongArray(static_cast<jsize>(tensor.dimensions.size()));
    if (!name || !dimensions) {
        env->DeleteLocalRef(tensorClass);
        return nullptr;
    }
    if (!tensor.dimensions.empty()) {
        std::vector<jlong> values;
        values.reserve(tensor.dimensions.size());
        for (uint32_t dimension : tensor.dimensions) values.push_back(dimension);
        env->SetLongArrayRegion(dimensions, 0, static_cast<jsize>(values.size()), values.data());
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(name);
            env->DeleteLocalRef(dimensions);
            env->DeleteLocalRef(tensorClass);
            return nullptr;
        }
    }
    jobject result = env->NewObject(
        tensorClass,
        constructor,
        name,
        static_cast<jint>(tensor.dataType),
        dimensions,
        static_cast<jint>(tensor.byteCount));
    env->DeleteLocalRef(name);
    env->DeleteLocalRef(dimensions);
    env->DeleteLocalRef(tensorClass);
    return result;
}

jobject makeGraphMetadata(JNIEnv* env, const GraphSession& session) {
    jclass tensorClass = env->FindClass("com/onetake/npu/QnnNativeTensorSpec");
    jclass graphClass = env->FindClass("com/onetake/npu/QnnNativeGraphMetadata");
    if (!tensorClass || !graphClass) {
        if (tensorClass) env->DeleteLocalRef(tensorClass);
        if (graphClass) env->DeleteLocalRef(graphClass);
        return nullptr;
    }
    const jmethodID tensorConstructor = env->GetMethodID(
        tensorClass, "<init>", "(Ljava/lang/String;I[JI)V");
    const std::string tensorClassName = "Lcom/onetake/npu/QnnNativeTensorSpec;";
    const std::string graphSignature = "(Ljava/lang/String;[" + tensorClassName + "[" +
        tensorClassName + ")V";
    const jmethodID graphConstructor = env->GetMethodID(
        graphClass, "<init>", graphSignature.c_str());
    if (!tensorConstructor || !graphConstructor) {
        env->DeleteLocalRef(tensorClass);
        env->DeleteLocalRef(graphClass);
        return nullptr;
    }
    jobjectArray inputs = env->NewObjectArray(
        static_cast<jsize>(session.inputs.size()), tensorClass, nullptr);
    jobjectArray outputs = env->NewObjectArray(
        static_cast<jsize>(session.outputs.size()), tensorClass, nullptr);
    jstring graphName = env->NewStringUTF(session.graphName.c_str());
    if (!inputs || !outputs || !graphName) {
        env->DeleteLocalRef(tensorClass);
        env->DeleteLocalRef(graphClass);
        return nullptr;
    }
    for (jsize index = 0; index < static_cast<jsize>(session.inputs.size()); ++index) {
        jobject value = makeTensorMetadata(env, session.inputs[static_cast<size_t>(index)]);
        if (!value) {
            if (!env->ExceptionCheck()) {
                throwGraphException(env, "Unable to create QNN input tensor metadata");
            }
            env->DeleteLocalRef(graphName);
            env->DeleteLocalRef(inputs);
            env->DeleteLocalRef(outputs);
            env->DeleteLocalRef(tensorClass);
            env->DeleteLocalRef(graphClass);
            return nullptr;
        }
        env->SetObjectArrayElement(inputs, index, value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(graphName);
            env->DeleteLocalRef(inputs);
            env->DeleteLocalRef(outputs);
            env->DeleteLocalRef(tensorClass);
            env->DeleteLocalRef(graphClass);
            return nullptr;
        }
    }
    for (jsize index = 0; index < static_cast<jsize>(session.outputs.size()); ++index) {
        jobject value = makeTensorMetadata(env, session.outputs[static_cast<size_t>(index)]);
        if (!value) {
            if (!env->ExceptionCheck()) {
                throwGraphException(env, "Unable to create QNN output tensor metadata");
            }
            env->DeleteLocalRef(graphName);
            env->DeleteLocalRef(inputs);
            env->DeleteLocalRef(outputs);
            env->DeleteLocalRef(tensorClass);
            env->DeleteLocalRef(graphClass);
            return nullptr;
        }
        env->SetObjectArrayElement(outputs, index, value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(graphName);
            env->DeleteLocalRef(inputs);
            env->DeleteLocalRef(outputs);
            env->DeleteLocalRef(tensorClass);
            env->DeleteLocalRef(graphClass);
            return nullptr;
        }
    }
    jobject result = env->NewObject(graphClass, graphConstructor, graphName, inputs, outputs);
    env->DeleteLocalRef(graphName);
    env->DeleteLocalRef(inputs);
    env->DeleteLocalRef(outputs);
    env->DeleteLocalRef(tensorClass);
    env->DeleteLocalRef(graphClass);
    if (!result && !env->ExceptionCheck()) {
        throwGraphException(env, "Unable to create QNN graph metadata");
    }
    return result;
}

#endif  // ONETAKE_QNN_AVAILABLE

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_onetake_npu_QnnGraphNative_open(
    JNIEnv* env, jobject, jstring nativeDirectory, jstring binaryPath, jstring graphName) {
    try {
#ifndef ONETAKE_QNN_AVAILABLE
    static_cast<void>(nativeDirectory);
    static_cast<void>(binaryPath);
    static_cast<void>(graphName);
    throwGraphException(env, "QNN graph runtime is unavailable: QAIRT SDK was not configured");
    return 0;
#else
    std::string nativeDirectoryValue;
    std::string binaryPathValue;
    std::string graphNameValue;
    if (!readString(env, nativeDirectory, &nativeDirectoryValue, false) ||
        !readString(env, binaryPath, &binaryPathValue, false) ||
        !readString(env, graphName, &graphNameValue, true)) {
        return 0;
    }
    auto session = std::make_unique<GraphSession>();
    std::string error;
    if (!openSession(nativeDirectoryValue, binaryPathValue, graphNameValue, session.get(), &error)) {
        throwGraphException(env, error.empty() ? "QNN graph session open failed" : error);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(session.release()));
#endif
    } catch (const std::bad_alloc&) {
        throwGraphException(env, "QNN graph runtime allocation failed");
        return 0;
    } catch (const std::exception&) {
        throwGraphException(env, "QNN graph runtime open failed");
        return 0;
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_onetake_npu_QnnGraphNative_metadata(JNIEnv* env, jobject, jlong handle) {
    try {
#ifndef ONETAKE_QNN_AVAILABLE
    static_cast<void>(handle);
    throwGraphException(env, "QNN graph runtime is unavailable: QAIRT SDK was not configured");
    return nullptr;
#else
    if (!validHandle(handle)) {
        throwGraphException(env, "QNN graph metadata requested for an invalid session");
        return nullptr;
    }
    GraphSession* session = sessionFrom(handle);
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->closed) {
        throwGraphException(env, "QNN graph session is closed");
        return nullptr;
    }
    return makeGraphMetadata(env, *session);
#endif
    } catch (const std::bad_alloc&) {
        throwGraphException(env, "QNN graph runtime allocation failed");
        return nullptr;
    } catch (const std::exception&) {
        throwGraphException(env, "QNN graph metadata failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_onetake_npu_QnnGraphNative_execute(
    JNIEnv* env, jobject, jlong handle, jobjectArray inputBuffers) {
    try {
#ifndef ONETAKE_QNN_AVAILABLE
    static_cast<void>(handle);
    static_cast<void>(inputBuffers);
    throwGraphException(env, "QNN graph runtime is unavailable: QAIRT SDK was not configured");
    return nullptr;
#else
    if (!validHandle(handle)) {
        throwGraphException(env, "QNN graph execution requested for an invalid session");
        return nullptr;
    }
    GraphSession* session = sessionFrom(handle);
    std::lock_guard<std::mutex> lock(session->mutex);
    if (session->closed) {
        throwGraphException(env, "QNN graph session is closed");
        return nullptr;
    }
    if (!inputBuffers) {
        throwGraphException(env, "QNN graph execution received null inputs");
        return nullptr;
    }
    const jsize inputCount = env->GetArrayLength(inputBuffers);
    if (inputCount != static_cast<jsize>(session->inputs.size())) {
        throwGraphException(env, "QNN graph execution received the wrong input count");
        return nullptr;
    }
    std::vector<std::vector<uint8_t>> inputData(session->inputs.size());
    std::vector<Qnn_Tensor_t> qnnInputs;
    qnnInputs.reserve(session->inputs.size());
    for (jsize index = 0; index < inputCount; ++index) {
        auto input = static_cast<jbyteArray>(env->GetObjectArrayElement(inputBuffers, index));
        if (!input) {
            throwGraphException(env, "QNN graph execution received a null input buffer");
            return nullptr;
        }
        const jsize size = env->GetArrayLength(input);
        const auto& spec = session->inputs[static_cast<size_t>(index)];
        if (size != static_cast<jsize>(spec.byteCount)) {
            env->DeleteLocalRef(input);
            throwGraphException(env, "QNN graph execution received an input with the wrong size");
            return nullptr;
        }
        inputData[static_cast<size_t>(index)].resize(spec.byteCount);
        env->GetByteArrayRegion(
            input,
            0,
            size,
            reinterpret_cast<jbyte*>(inputData[static_cast<size_t>(index)].data()));
        env->DeleteLocalRef(input);
        if (env->ExceptionCheck()) return nullptr;
        qnnInputs.push_back(makeTensor(
            spec,
            inputData[static_cast<size_t>(index)].data(),
            spec.byteCount));
    }
    std::vector<std::vector<uint8_t>> outputData(session->outputs.size());
    std::vector<Qnn_Tensor_t> qnnOutputs;
    qnnOutputs.reserve(session->outputs.size());
    for (size_t index = 0; index < session->outputs.size(); ++index) {
        const auto& spec = session->outputs[index];
        outputData[index].resize(spec.byteCount);
        qnnOutputs.push_back(makeTensor(
            spec,
            outputData[index].data(),
            spec.byteCount));
    }
    const auto& api = session->qnn->QNN_INTERFACE_VER_NAME;
    const Qnn_ErrorHandle_t result = api.graphExecute(
        session->graph,
        qnnInputs.data(),
        static_cast<uint32_t>(qnnInputs.size()),
        qnnOutputs.data(),
        static_cast<uint32_t>(qnnOutputs.size()),
        nullptr,
        nullptr);
    if (result != QNN_SUCCESS) {
        throwGraphException(env, qnnError("graphExecute", result));
        return nullptr;
    }
    jclass byteArrayClass = env->FindClass("[B");
    if (!byteArrayClass) return nullptr;
    jobjectArray resultArray = env->NewObjectArray(
        static_cast<jsize>(outputData.size()), byteArrayClass, nullptr);
    if (!resultArray) {
        env->DeleteLocalRef(byteArrayClass);
        return nullptr;
    }
    for (jsize index = 0; index < static_cast<jsize>(outputData.size()); ++index) {
        const auto& output = outputData[static_cast<size_t>(index)];
        jbyteArray array = env->NewByteArray(static_cast<jsize>(output.size()));
        if (!array) break;
        env->SetByteArrayRegion(
            array,
            0,
            static_cast<jsize>(output.size()),
            reinterpret_cast<const jbyte*>(output.data()));
        env->SetObjectArrayElement(resultArray, index, array);
        env->DeleteLocalRef(array);
        if (env->ExceptionCheck()) break;
    }
    env->DeleteLocalRef(byteArrayClass);
    return resultArray;
#endif
    } catch (const std::bad_alloc&) {
        throwGraphException(env, "QNN graph runtime allocation failed");
        return nullptr;
    } catch (const std::exception&) {
        throwGraphException(env, "QNN graph execution failed");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_onetake_npu_QnnGraphNative_close(JNIEnv* env, jobject, jlong handle) {
    try {
#ifndef ONETAKE_QNN_AVAILABLE
    static_cast<void>(handle);
    throwGraphException(env, "QNN graph runtime is unavailable: QAIRT SDK was not configured");
#else
    if (!validHandle(handle)) return;
    std::unique_ptr<GraphSession> session(sessionFrom(handle));
    std::string error;
    {
        std::lock_guard<std::mutex> lock(session->mutex);
        if (!session->closed) {
            session->closed = true;
            releaseSession(session.get(), &error);
        }
    }
    if (!error.empty()) {
        throwGraphException(env, error);
    }
#endif
    } catch (const std::bad_alloc&) {
        throwGraphException(env, "QNN graph runtime allocation failed during release");
    } catch (const std::exception&) {
        throwGraphException(env, "QNN graph runtime release failed");
    }
}
