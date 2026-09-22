#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

from extract_utils.fixups_blob import (
    blob_fixup,
    blob_fixups_user_type,
)
from extract_utils.fixups_lib import (
    lib_fixup_remove,
    lib_fixups,
    lib_fixups_user_type,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

namespace_imports = [
    'device/sony/yoshino-common',
    'hardware/qcom-caf/msm8998',
    'hardware/qcom-caf/wlan',
    'vendor/qcom/opensource/dataservices',
    "vendor/sony/camera",
]


def lib_fixup_vendor_suffix(lib: str, partition: str, *args, **kwargs):
    return f'{lib}_{partition}' if partition == 'vendor' else None


lib_fixups: lib_fixups_user_type = {
    **lib_fixups,
    (
        'com.qualcomm.qti.dpm.api@1.0',
        'vendor.qti.imsrtpservice@3.0',
        'vendor.qti.hardware.fm@1.0',
    ): lib_fixup_vendor_suffix,
    ('libwifi-hal-ctrl'): lib_fixup_remove,
}

blob_fixups: blob_fixups_user_type = {
    'system_ext/lib64/lib-imscamera.so': blob_fixup()
        .add_needed('libgui_shim.so'),
    'system_ext/lib64/lib-imsvideocodec.so': blob_fixup()
        .add_needed('libgui_shim.so')
        .add_needed('libui_shim.so')
        .replace_needed('libqdMetaData.so', 'libqdMetaData.system.so'),
    ('vendor/bin/hw/fpc_fingerprint@2.1_HIDL-service', 'vendor/bin/thermal-engine',
     'vendor/bin/hw/vendor.semc.hardware.secd@1.0-service', 'vendor/lib64/lib_fpc_tac_shared.so'): blob_fixup()
        .replace_needed('libprotobuf-c.so', 'libprotobuf-c-idd.so'),
    'vendor/bin/hw/android.hardware.drm@1.1-service.widevine': blob_fixup()
        .replace_needed('libhidltransport.so', 'libhidlbase.so')
        .remove_needed('libhwbinder.so'),
    ('vendor/bin/keyprovd', 'vendor/lib64/libFIDOKeyProvisioning.so'): blob_fixup()
        .binary_regex_replace(b'/system/etc/firmware', b'/vendor/etc/firmware'),
    'vendor/bin/sony-modem-switcher': blob_fixup()
        .binary_regex_replace(b'/oem/modem-config/%s/modem.conf', b'/vendor/modemconf/%s/modem.conf')
        .binary_regex_replace(b'/oem/modem-config/modem.conf', b'/vendor/modemconf/modem.conf')
        .binary_regex_replace(b'/cache/modem/', b'/data/vendor/')
        .binary_regex_replace(b'/system/etc/customization/', b'/vendor/etc/customization/'),
    ('vendor/bin/qns', 'vendor/lib/libchromaflash.so', 'vendor/lib/liboptizoom.so', 'vendor/lib/libseemore.so',
     'vendor/lib/libsomc_alfortlp.so', 'vendor/lib/libsomc_alfortlpserv.so', 'vendor/lib/libsomc_alfortrsc.so',
     'vendor/lib/libsomc_bordeauxrsc.so', 'vendor/lib/libsomc_buttercakersc.so', 'vendor/lib/libsomc_canelersc.so',
     'vendor/lib/libsomc_cheesesconersc.so', 'vendor/lib/libsomc_dars.so', 'vendor/lib/libsomc_darsrsc.so',
     'vendor/lib/libsomc_marblersc.so', 'vendor/lib/libsomc_melonpanrsc.so', 'vendor/lib/libsomc_mugichocorsc.so',
     'vendor/lib/libsomc_pretzchocorsc.so', 'vendor/lib/libsomc_raisinrsc.so', 'vendor/lib/libsomc_shortcakersc.so',
     'vendor/lib/libsomc_spicarsc.so', 'vendor/lib/libsomc_sumomolpserv.so', 'vendor/lib/libsomc_sumomorsc.so',
     'vendor/lib/libsomc_topporsc.so', 'vendor/lib/libsomc_yummyrsc.so', 'vendor/lib/libsony_fooddetect.so',
     'vendor/lib/libsony_naruto.so', 'vendor/lib/libvideobokeh.so'): blob_fixup()
        .replace_needed('libstdc++.so', 'libstdc++_vendor.so'),
    'vendor/etc/init/android.hardware.drm@1.1-service.widevine.rc': blob_fixup()
        .regex_replace('writepid /dev/cpuset/foreground/tasks', 'task_profiles ProcessCapacityHigh'),
    'vendor/etc/init/init.sony.idd.rc': blob_fixup()
        .regex_replace('restorecon_recursive --force', 'restorecon_recursive')
        .regex_replace('writepid /dev/cpuset/system-background/tasks', 'task_profiles ServiceCapacityLow'),
    'vendor/etc/init/vendor.semc.system.idd@1.0-service.rc': blob_fixup()
        .regex_replace('writepid /dev/cpuset/system-background/tasks', 'task_profiles ServiceCapacityLow'),
    'vendor/etc/init/vendor.somc.hardware.camera.provider@1.0-service.rc': blob_fixup()
        .regex_replace('writepid /dev/cpuset/camera-daemon/tasks /dev/stune/top-app/tasks', 'task_profiles CameraServiceCapacity MaxPerformance'),
    'vendor/etc/msm_irqbalance.conf': blob_fixup()
        .regex_replace('IGNORED_IRQ=19,22,39,200,203\n', 'IGNORED_IRQ=2,19,22,39,200,203,332\n'),
    'vendor/lib/libmmcamera_faceproc.so': blob_fixup()
        .clear_symbol_version('__aeabi_memcpy')
        .clear_symbol_version('__aeabi_memset')
        .clear_symbol_version('__gnu_Unwind_Find_exidx'),
    'vendor/lib/libznr.so': blob_fixup()
        .add_needed('liblog.so'),
    ('vendor/lib/vendor.semc.hardware.light@1.0.so', 'vendor/lib/vendor.semc.system.idd@1.0.so',
     'vendor/lib/vendor.somc.hardware.camera.cacao@1.0.so', 'vendor/lib/vendor.somc.hardware.camera.cacao@2.0.so',
     'vendor/lib/vendor.somc.hardware.camera.cacao@3.0.so', 'vendor/lib/vendor.somc.hardware.camera.cacao@3.1.so',
     'vendor/lib/vendor.somc.hardware.camera.device@1.0.so', 'vendor/lib/vendor.somc.hardware.camera.provider@1.0.so',
     'vendor/lib/vendor.somc.hardware.security.secd@1.0.so', 'vendor/lib64/com.fingerprints.extension@1.0.so',
     'vendor/lib64/vendor.semc.hardware.light@1.0.so', 'vendor/lib64/vendor.semc.system.idd@1.0.so',
     'vendor/lib64/vendor.somc.hardware.miscta@1.0.so', 'vendor/lib64/vendor.somc.hardware.security.secd@1.0.so'): blob_fixup()
        .add_needed('libhidlbase_shim.so'),
    ('vendor/lib/libwvhidl.so', 'vendor/lib64/libwvhidl.so'): blob_fixup()
        .add_needed('libcrypto_shim.so'),
}  # fmt: skip

module = ExtractUtilsModule(
    'yoshino-common',
    'sony',
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
    namespace_imports=namespace_imports,
)

if __name__ == '__main__':
    utils = ExtractUtils.device(module)
    utils.run()
