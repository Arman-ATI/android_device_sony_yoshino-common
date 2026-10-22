package com.sonymobile.customizationselector;

/** JNI：libdiagefs.so 的入口。runBatch() 讀 files/efs_cmd.txt，結果寫到 files/efs_result.txt。 */
final class EfsNative {
    static {
        System.loadLibrary("diagefs");
    }

    static native int runBatch();

    private EfsNative() {}
}
