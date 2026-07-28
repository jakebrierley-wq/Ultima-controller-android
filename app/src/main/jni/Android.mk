LOCAL_PATH := $(call my-dir)
PROJECT_ROOT := $(LOCAL_PATH)/../../../..
CORE_DIR := $(PROJECT_ROOT)/third_party/dosbox-pure

CORE_SOURCES := \
    $(CORE_DIR)/*.cpp \
    $(CORE_DIR)/src/*.cpp \
    $(CORE_DIR)/src/*/*.cpp \
    $(CORE_DIR)/src/*/*/*.cpp
CORE_SOURCES := $(wildcard $(CORE_SOURCES))

include $(CLEAR_VARS)
LOCAL_MODULE := ultima_core
LOCAL_SRC_FILES := native_frontend.cpp $(CORE_SOURCES)
LOCAL_C_INCLUDES := \
    $(CORE_DIR) \
    $(CORE_DIR)/include \
    $(CORE_DIR)/libretro-common/include
LOCAL_CFLAGS := \
    -D__LIBRETRO__ \
    -D_FILE_OFFSET_BITS=64 \
    -DNDEBUG \
    -O2 \
    -fexceptions \
    -fomit-frame-pointer \
    -fvisibility=hidden \
    -ffunction-sections \
    -fdata-sections \
    -Wno-address-of-packed-member \
    -Wno-format \
    -Wno-psabi \
    -Wno-switch
LOCAL_CPPFLAGS := $(LOCAL_CFLAGS) -std=c++17
LOCAL_LDFLAGS := \
    -Wl,--gc-sections \
    -Wl,-z,max-page-size=16384
LOCAL_LDLIBS := -landroid -llog
LOCAL_CPP_FEATURES := exceptions rtti

include $(BUILD_SHARED_LIBRARY)
