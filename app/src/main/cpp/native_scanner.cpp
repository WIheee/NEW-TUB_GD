#include <jni.h>

#include <dirent.h>
#include <sys/stat.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <thread>
#include <unordered_set>
#include <vector>

namespace {

constexpr size_t kMaxThreads = 4;

struct FileEntry {
    std::string path;
    long long size;
};

unsigned char resolveType(const std::string& fullPath) {
    struct stat st {};
    if (stat(fullPath.c_str(), &st) != 0) {
        return DT_LNK;
    }
    if (S_ISDIR(st.st_mode)) return DT_DIR;
    if (S_ISREG(st.st_mode)) return DT_REG;
    return DT_LNK;
}

// 递归遍历单个目录，跳过重排除目录与符号链接，结果写入独立 bucket（线程安全）
void walk(const std::string& root,
          const std::string& rel,
          const std::unordered_set<std::string>& skipDirs,
          std::vector<FileEntry>& out) {
    const std::string dirPath = rel.empty() ? root : (root + "/" + rel);
    DIR* dir = opendir(dirPath.c_str());
    if (dir == nullptr) {
        return;
    }

    struct dirent* entry = nullptr;
    while ((entry = readdir(dir)) != nullptr) {
        const std::string name(entry->d_name);
        if (name == "." || name == "..") {
            continue;
        }

        const std::string childRel = rel.empty() ? name : (rel + "/" + name);
        const std::string fullPath = root + "/" + childRel;
        unsigned char type = entry->d_type;
        if (type == DT_UNKNOWN) {
            type = resolveType(fullPath);
        }

        if (type == DT_DIR) {
            if (skipDirs.find(name) != skipDirs.end()) {
                continue;
            }
            walk(root, childRel, skipDirs, out);
        } else if (type == DT_REG) {
            // 这里顺手 stat 一次拿大小，避免 Kotlin 侧再 stat 一次
            struct stat st {};
            long long size = -1;
            if (stat(fullPath.c_str(), &st) == 0) {
                size = static_cast<long long>(st.st_size);
            }
            out.push_back(FileEntry{childRel, size});
        }
    }

    closedir(dir);
}

// 列出根目录下的文件与一级子目录，作为并行任务的入口
void listRootLevel(const std::string& root,
                   const std::unordered_set<std::string>& skipDirs,
                   std::vector<FileEntry>& files,
                   std::vector<std::string>& dirs) {
    DIR* dir = opendir(root.c_str());
    if (dir == nullptr) {
        return;
    }

    struct dirent* entry = nullptr;
    while ((entry = readdir(dir)) != nullptr) {
        const std::string name(entry->d_name);
        if (name == "." || name == "..") {
            continue;
        }
        const std::string fullPath = root + "/" + name;
        unsigned char type = entry->d_type;
        if (type == DT_UNKNOWN) {
            type = resolveType(fullPath);
        }
        if (type == DT_DIR) {
            if (skipDirs.find(name) == skipDirs.end()) {
                dirs.push_back(name);
            }
        } else if (type == DT_REG) {
            struct stat st {};
            long long size = -1;
            if (stat(fullPath.c_str(), &st) == 0) {
                size = static_cast<long long>(st.st_size);
            }
            files.push_back(FileEntry{name, size});
        }
    }

    closedir(dir);
}

}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_godot_tub_gd_utils_NativeScanner_nativeListRelativePaths(
        JNIEnv* env, jobject /* thiz */, jstring rootPath, jobjectArray excludes) {
    if (rootPath == nullptr) {
        return nullptr;
    }

    const char* rootChars = env->GetStringUTFChars(rootPath, nullptr);
    if (rootChars == nullptr) {
        return nullptr;
    }
    const std::string root(rootChars);
    env->ReleaseStringUTFChars(rootPath, rootChars);

    std::unordered_set<std::string> skipDirs;
    if (excludes != nullptr) {
        const jsize count = env->GetArrayLength(excludes);
        skipDirs.reserve(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            auto item = static_cast<jstring>(env->GetObjectArrayElement(excludes, i));
            if (item == nullptr) {
                continue;
            }
            const char* chars = env->GetStringUTFChars(item, nullptr);
            if (chars != nullptr) {
                skipDirs.emplace(chars);
                env->ReleaseStringUTFChars(item, chars);
            }
            env->DeleteLocalRef(item);
        }
    }

    std::vector<FileEntry> files;
    std::vector<std::string> firstLevelDirs;
    listRootLevel(root, skipDirs, files, firstLevelDirs);
    files.reserve(files.size() + 256);

    // 一级子目录分给多个线程并行遍历
    if (!firstLevelDirs.empty()) {
        size_t threadCount = std::thread::hardware_concurrency();
        if (threadCount == 0) threadCount = 2;
        threadCount = std::max<size_t>(2, std::min(threadCount, kMaxThreads));
        threadCount = std::min(threadCount, firstLevelDirs.size());

        std::vector<std::vector<FileEntry>> buckets(threadCount);
        std::atomic<size_t> nextIndex{0};

        std::vector<std::thread> workers;
        workers.reserve(threadCount);
        for (size_t i = 0; i < threadCount; ++i) {
            workers.emplace_back([&, i]() {
                while (true) {
                    const size_t index = nextIndex.fetch_add(1, std::memory_order_relaxed);
                    if (index >= firstLevelDirs.size()) {
                        break;
                    }
                    walk(root, firstLevelDirs[index], skipDirs, buckets[i]);
                }
            });
        }
        for (auto& worker : workers) {
            worker.join();
        }
        for (auto& bucket : buckets) {
            files.insert(files.end(), bucket.begin(), bucket.end());
        }
    }

    std::sort(files.begin(), files.end(), [](const FileEntry& a, const FileEntry& b) {
        return a.path < b.path;
    });

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(files.size()), stringClass, nullptr);
    if (result == nullptr) {
        return nullptr;
    }

    for (size_t i = 0; i < files.size(); ++i) {
        // 编码格式：<size>\x01<relativePath>；从第一个 \x01 切开，路径原样保留
        std::string encoded = std::to_string(files[i].size);
        encoded.push_back('\x01');
        encoded += files[i].path;
        jstring value = env->NewStringUTF(encoded.c_str());
        if (value == nullptr) {
            continue;
        }
        env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }

    return result;
}
