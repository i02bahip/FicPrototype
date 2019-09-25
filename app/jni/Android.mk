LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
NICE            := libnice-0.1.16
LOCAL_MODULE    := FicPrototype
LOCAL_SHARED_LIBRARIES := gstreamer_android
LOCAL_LDLIBS := -llog -landroid
LOCAL_CFLAGS += -DHAVE_CONFIG_H

NICE_DIRS               := $(LOCAL_PATH)/$(NICE)/ \
                            $(LOCAL_PATH)/$(NICE)/agent/ \
                            $(LOCAL_PATH)/$(NICE)/gst/ \
                            $(LOCAL_PATH)/$(NICE)/nice/ \
                            $(LOCAL_PATH)/$(NICE)/random/ \
                            $(LOCAL_PATH)/$(NICE)/socket/ \
                            $(LOCAL_PATH)/$(NICE)/stun/ \
                            $(LOCAL_PATH)/$(NICE)/stun/usages/
#                           $(LOCAL_PATH)/$(NICE)/stun/tools/ \

NICE_INCLUDES           := $(NICE_DIRS)
NICE_SRC                := $(filter-out %test.c, $(foreach dir, $(NICE_DIRS), $(patsubst $(LOCAL_PATH)/%, %, $(wildcard $(addsuffix *.c, $(dir)))) ))

LOCAL_C_INCLUDES        := $(NICE_INCLUDES) #add your own headers if needed
LOCAL_SRC_FILES := FicPrototype.c \
                    $(NICE_SRC)

include $(BUILD_SHARED_LIBRARY)

ifndef GSTREAMER_ROOT_ANDROID
$(error GSTREAMER_ROOT_ANDROID is not defined!)
endif

ifeq ($(TARGET_ARCH_ABI),armeabi)
GSTREAMER_ROOT        := $(GSTREAMER_ROOT_ANDROID)/arm
else ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
GSTREAMER_ROOT        := $(GSTREAMER_ROOT_ANDROID)/armv7
else ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
GSTREAMER_ROOT        := $(GSTREAMER_ROOT_ANDROID)/arm64
else ifeq ($(TARGET_ARCH_ABI),x86)
GSTREAMER_ROOT        := $(GSTREAMER_ROOT_ANDROID)/x86
else ifeq ($(TARGET_ARCH_ABI),x86_64)
GSTREAMER_ROOT        := $(GSTREAMER_ROOT_ANDROID)/x86_64
else
$(error Target arch ABI not supported: $(TARGET_ARCH_ABI))
endif

GSTREAMER_NDK_BUILD_PATH  := $(GSTREAMER_ROOT)/share/gst-android/ndk-build/
include $(GSTREAMER_NDK_BUILD_PATH)/plugins.mk
GSTREAMER_PLUGINS         := $(GSTREAMER_PLUGINS_CORE) $(GSTREAMER_PLUGINS_SYS) $(GSTREAMER_PLUGINS_EFFECTS) $(GSTREAMER_PLUGINS_NET) $(GSTREAMER_PLUGINS_NET_RESTRICTED) $(GSTREAMER_PLUGINS_PLAYBACK) $(GSTREAMER_PLUGINS_CODECS) $(GSTREAMER_PLUGINS_CODECS_RESTRICTED)
G_IO_MODULES              := gnutls
GSTREAMER_EXTRA_DEPS      := nice gstreamer-video-1.0 libsoup-2.4 glib-2.0 json-glib-1.0
include $(GSTREAMER_NDK_BUILD_PATH)/gstreamer-1.0.mk
