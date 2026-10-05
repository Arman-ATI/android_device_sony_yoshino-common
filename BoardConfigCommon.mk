#
# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

COMMON_PATH := device/sony/yoshino-common

# Architecture
TARGET_ARCH := arm64
TARGET_ARCH_VARIANT := armv8-a
TARGET_CPU_ABI := arm64-v8a
TARGET_CPU_ABI2 :=
TARGET_CPU_VARIANT := generic
TARGET_CPU_VARIANT_RUNTIME := cortex-a73

TARGET_2ND_ARCH := arm
TARGET_2ND_ARCH_VARIANT := armv8-a
TARGET_2ND_CPU_ABI := armeabi-v7a
TARGET_2ND_CPU_ABI2 := armeabi
TARGET_2ND_CPU_VARIANT := generic
TARGET_2ND_CPU_VARIANT_RUNTIME := cortex-a73

# Audio
AUDIO_FEATURE_ENABLED_EXTENDED_COMPRESS_FORMAT := true
BOARD_SUPPORTS_SOUND_TRIGGER := true
BOARD_USES_ALSA_AUDIO := true

# Bootloader
TARGET_BOOTLOADER_BOARD_NAME := msm8998
TARGET_NO_BOOTLOADER := true

# Camera
MALLOC_SVELTE_FOR_LIBC32 := true

# Display
TARGET_HAS_WIDE_COLOR_DISPLAY := true

# Filesystem
TARGET_FS_CONFIG_GEN := $(COMMON_PATH)/config.fs

# HIDL
DEVICE_FRAMEWORK_COMPATIBILITY_MATRIX_FILE += \
    $(COMMON_PATH)/framework_compatibility_matrix.xml \
    hardware/qcom-caf/common/vendor_framework_compatibility_matrix.xml \
    hardware/qcom-caf/common/vendor_framework_compatibility_matrix_legacy.xml \
    vendor/lineage/config/device_framework_matrix.xml
DEVICE_MANIFEST_FILE := $(COMMON_PATH)/manifest.xml
DEVICE_MATRIX_FILE := hardware/qcom-caf/common/compatibility_matrix.xml

# Kernel
BOARD_KERNEL_CMDLINE += androidboot.hardware=qcom msm_rtb.filter=0x37 ehci-hcd.park=3
BOARD_KERNEL_CMDLINE += lpm_levels.sleep_disabled=1 sched_enable_power_aware=1
BOARD_KERNEL_CMDLINE += service_locator.enable=1 swiotlb=2048 androidboot.configfs=true
BOARD_KERNEL_CMDLINE += androidboot.usbcontroller=a800000.dwc3 loop.max_part=7
BOARD_KERNEL_BASE := 0x00000000
BOARD_KERNEL_PAGESIZE := 4096
BOARD_KERNEL_IMAGE_NAME := Image.gz-dtb
TARGET_KERNEL_VERSION := 4.4
TARGET_KERNEL_CONFIG += msmcortex-perf_defconfig sony/yoshino.config
TARGET_KERNEL_SOURCE := kernel/sony/msm8998

# Partitions
BOARD_FLASH_BLOCK_SIZE := 262144
BOARD_BOOTIMAGE_PARTITION_SIZE := 67108864
BOARD_CACHEIMAGE_PARTITION_SIZE := 398458880
BOARD_RECOVERYIMAGE_PARTITION_SIZE := 67108864
BOARD_CACHEIMAGE_FILE_SYSTEM_TYPE := ext4
BOARD_SYSTEMIMAGE_FILE_SYSTEM_TYPE := ext4
TARGET_USERIMAGES_USE_EXT4 := true

# Platform
BOARD_USES_QCOM_HARDWARE := true
TARGET_BOARD_PLATFORM := msm8998

# Properties
TARGET_ODM_PROP += $(COMMON_PATH)/odm.prop
TARGET_PRODUCT_PROP += $(COMMON_PATH)/product.prop
TARGET_SYSTEM_PROP += $(COMMON_PATH)/system.prop
TARGET_VENDOR_PROP += $(COMMON_PATH)/vendor.prop

# Recovery
TARGET_RECOVERY_PIXEL_FORMAT := RGBX_8888
TARGET_USERIMAGES_USE_EXT4 := true

# RIL
ENABLE_VENDOR_RIL_SERVICE := true

# SEPolicy
include device/lineage/sepolicy/libperfmgr/sepolicy.mk
include device/qcom/sepolicy-legacy-um/SEPolicy.mk
BOARD_VENDOR_SEPOLICY_DIRS += $(COMMON_PATH)/sepolicy/vendor

# Treble
TARGET_COPY_OUT_VENDOR := vendor

# Vendor security patch
VENDOR_SECURITY_PATCH := 2019-09-01

# Verity
# Only needed for signing
BOARD_AVB_ENABLE := false

# Wi-Fi
BOARD_WLAN_DEVICE := qcwcn
BOARD_HOSTAPD_DRIVER := NL80211
BOARD_HOSTAPD_PRIVATE_LIB := lib_driver_cmd_$(BOARD_WLAN_DEVICE)
BOARD_WPA_SUPPLICANT_DRIVER := NL80211
BOARD_WPA_SUPPLICANT_PRIVATE_LIB := lib_driver_cmd_$(BOARD_WLAN_DEVICE)
WIFI_HIDL_FEATURE_DUAL_INTERFACE := true
WIFI_HIDL_UNIFIED_SUPPLICANT_SERVICE_RC_ENTRY := true
WPA_SUPPLICANT_VERSION := VER_0_8_X

# Inherit from the proprietary version
include vendor/sony/yoshino-common/BoardConfigVendor.mk
