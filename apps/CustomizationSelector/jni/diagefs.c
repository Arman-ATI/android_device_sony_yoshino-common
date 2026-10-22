/* root 透過 /dev/diag 讀寫 modem EFS 的 helper（協定依 msm8998 diag 驅動 diagchar_core.c / diag_memorydevice.c）。
 * 流程：DIAG_IOCTL_SWITCH_LOGGING→MEMORY_DEVICE_MODE 建立 md session；寫入前綴 USER_SPACE_DATA_TYPE + HDLC 請求；
 * 驅動回傳的 record 與 HDLC 框架混在一起，依 CRC16 驗證後取出回應；結束切回 USB_MODE。
 * 以 LD_PRELOAD 載入 /system/bin/true，constructor 逐行執行 /data/local/tmp/efs_cmd.txt：
 *   backup <efs路徑> <本機檔>    讀 EFS 存到本機；不存在則建立 <本機檔>.absent
 *   write  <efs路徑> <本機檔>    建立父目錄、寫入並讀回驗證
 *   verify <efs路徑> <本機檔>    讀回比對
 *   unlink <efs路徑>             刪除（不存在視為成功）
 * 結果逐行寫到 /data/local/tmp/efs_result.txt（OK / FAIL 開頭） */
#include <fcntl.h>
#include <pthread.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <signal.h>
#include <time.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define DIAG_DEV "/dev/diag"
#ifndef APP_DIR
#define APP_DIR "/data/data/com.sonymobile.customizationselector/files"
#endif
#define CMD_CFG APP_DIR "/efs_cmd.txt"
#define OUT_PATH APP_DIR "/efs_result.txt"

#define DIAG_IOCTL_SWITCH_LOGGING 7
#define USB_MODE 1
#define MEMORY_DEVICE_MODE 2
#define DIAG_CON_MPSS 0x0002
#define USER_SPACE_DATA_TYPE 0x20
#define EFS_MAX (1 << 20)

struct diag_logging_mode_param_t {
    uint32_t req_mode;
    uint32_t peripheral_mask;
    uint32_t pd_mask;
    uint8_t mode_param;
} __attribute__((packed));

static int g_fd = -1;
static FILE *g_out;
static uint8_t g_stream[65536];
static size_t g_slen = 0;
static uint8_t g_data[EFS_MAX];

static uint16_t crc16(const uint8_t *d, size_t n) {
    uint16_t c = 0xFFFF;
    for (size_t i = 0; i < n; i++) {
        c ^= d[i];
        for (int k = 0; k < 8; k++) c = (c & 1) ? (c >> 1) ^ 0x8408 : c >> 1;
    }
    return (uint16_t)~c;
}

static size_t hdlc_encode(const uint8_t *p, size_t n, uint8_t *out) {
    uint8_t tmp[8192];
    uint16_t c = crc16(p, n);
    memcpy(tmp, p, n);
    tmp[n] = c & 0xFF;
    tmp[n + 1] = c >> 8;
    size_t o = 0;
    for (size_t i = 0; i < n + 2; i++) {
        if (tmp[i] == 0x7E || tmp[i] == 0x7D) { out[o++] = 0x7D; out[o++] = tmp[i] ^ 0x20; }
        else out[o++] = tmp[i];
    }
    out[o++] = 0x7E;
    return o;
}

static int i32(const uint8_t *p) { return (int)(p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24); }
static void put32(uint8_t *p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }

static void absorb(const uint8_t *buf, size_t n) {
    if (g_slen + n > sizeof(g_stream)) n = sizeof(g_stream) - g_slen;
    memcpy(g_stream + g_slen, buf, n);
    g_slen += n;
}

/* 取出下一個 CRC 正確的 EFS 回應（從 4B 13 開始、不含 CRC）；0 表示沒有 */
static size_t next_frame(uint8_t *dst) {
    for (size_t i = 0; i < g_slen; i++) {
        if (g_stream[i] != 0x7E) continue;
        static uint8_t tmp[16384];
        size_t o = 0;
        int esc = 0;
        for (size_t j = 0; j < i; j++) {
            if (esc) { tmp[o++] = g_stream[j] ^ 0x20; esc = 0; }
            else if (g_stream[j] == 0x7D) esc = 1;
            else tmp[o++] = g_stream[j];
        }
        memmove(g_stream, g_stream + i + 1, g_slen - i - 1);
        g_slen -= i + 1;
        size_t found = 0;
        for (size_t k = 0; k + 6 <= o; k++) {
            if (tmp[k] != 0x4B || tmp[k + 1] != 0x13) continue;
            size_t body = o - k;
            uint16_t c = crc16(tmp + k, body - 2);
            if (tmp[o - 2] == (c & 0xFF) && tmp[o - 1] == (c >> 8)) {
                memcpy(dst, tmp + k, body - 2);
                found = body - 2;
            }
        }
        if (found) return found;
    }
    return 0;
}


/* diag 驅動沒有 poll，read 會阻塞。alarm() 是 process 層級、SIGALRM 可能送到其他執行緒，
 * 所以另開計時執行緒，用 pthread_kill 指定送給正在 read 的這條執行緒來中斷。 */
struct alarm_arg { pid_t pid; pid_t tid; unsigned secs; volatile int done; };

static void *alarm_fn(void *p) {
    struct alarm_arg *a = p;
    for (unsigned i = 0; i < a->secs * 10 && !a->done; i++) {
        struct timespec ts = { 0, 100000000 };
        nanosleep(&ts, NULL);
    }
    if (!a->done) syscall(SYS_tgkill, a->pid, a->tid, SIGALRM);   /* 指定執行緒 */
    return NULL;
}

static void on_alarm(int sig) { (void)sig; }

static ssize_t read_timed(void *buf, size_t n, unsigned secs) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_alarm;
    sigaction(SIGALRM, &sa, NULL);
    struct alarm_arg a;
    a.pid = (pid_t)syscall(SYS_getpid);
    a.tid = (pid_t)syscall(SYS_gettid);
    a.secs = secs;
    a.done = 0;
    pthread_t th;
    if (pthread_create(&th, NULL, alarm_fn, &a) != 0) return read(g_fd, buf, n);
    ssize_t r = read(g_fd, buf, n);
    a.done = 1;
    pthread_join(th, NULL);
    return r;
}

static size_t read_frame(uint8_t *dst, int timeout_s) {
    static uint8_t rb[16384];
    for (int i = 0; i < timeout_s; i++) {
        size_t f = next_frame(dst);
        if (f) return f;
        ssize_t r = read_timed(rb, sizeof(rb), 1);
        if (r > 0) absorb(rb, (size_t)r);
    }
    return next_frame(dst);
}

static int diag_set_mode(uint32_t mode) {
    struct diag_logging_mode_param_t p;
    memset(&p, 0, sizeof(p));
    p.req_mode = mode;
    p.peripheral_mask = DIAG_CON_MPSS;
    return ioctl(g_fd, DIAG_IOCTL_SWITCH_LOGGING, &p);
}

/* 送 EFS 請求（subsys 0x4B, id 0x13）並等相同 cmd 的回應 */
static size_t efs_call(uint16_t cmd, const uint8_t *body, size_t blen, uint8_t *resp) {
    uint8_t req[4096], fr[8192], pkt[8192 + 4];
    req[0] = 0x4B; req[1] = 0x13; req[2] = cmd & 0xFF; req[3] = cmd >> 8;
    memcpy(req + 4, body, blen);
    size_t n = hdlc_encode(req, 4 + blen, fr);
    put32(pkt, USER_SPACE_DATA_TYPE);
    memcpy(pkt + 4, fr, n);
    g_slen = 0;   /* 丟棄先前遲到的回應，避免錯配 */
    for (int tries = 0; tries < 3; tries++) {
        if (write(g_fd, pkt, n + 4) < 0) return 0;   /* 驅動成功時回傳 0 */
        for (;;) {
            size_t r = read_frame(resp, 3);
            if (r == 0) break;
            if (r >= 4 && memcmp(resp, req, 4) == 0) return r;
        }
    }
    return 0;
}

static int efs_open(const char *path, int flags, int mode, int *err) {
    uint8_t body[512], resp[8192];
    size_t pl = strlen(path) + 1;
    put32(body, flags); put32(body + 4, mode);
    memcpy(body + 8, path, pl);
    size_t r = efs_call(2, body, 8 + pl, resp);
    if (r < 12) { *err = -1; return -1; }
    *err = i32(resp + 8);
    return i32(resp + 4);
}

static void efs_close(int fd) {
    uint8_t body[4], resp[256];
    put32(body, fd);
    efs_call(3, body, 4, resp);
}

static int efs_mkdir(const char *path) {
    uint8_t body[512], resp[256];
    size_t pl = strlen(path) + 1;
    body[0] = 0xED; body[1] = 0x01;   /* mode 0755（int16 小端序，同 python struct.pack('<h')） */
    memcpy(body + 2, path, pl);
    size_t r = efs_call(9, body, 2 + pl, resp);
    return r ? i32(resp + 4) : -1;
}

static int efs_unlink(const char *path) {
    uint8_t body[512], resp[256];
    size_t pl = strlen(path) + 1;
    memcpy(body, path, pl);
    size_t r = efs_call(8, body, pl, resp);
    return r ? i32(resp + 4) : -1;
}

/* 讀整個 EFS 檔；回傳長度，錯誤碼放在 *err */
static size_t efs_read_file(const char *path, int *err) {
    int fd = efs_open(path, 0, 0, err);
    if (*err) return 0;
    size_t total = 0;
    uint8_t body[12], resp[8192];
    for (;;) {
        put32(body, fd); put32(body + 4, 1024); put32(body + 8, total);
        size_t r = efs_call(4, body, 12, resp);
        if (r < 20) { *err = -3; break; }
        int n = i32(resp + 12), er = i32(resp + 16);   /* [cmd4][fd][off][n][err][data] */
        if (er) { *err = er; break; }
        if (n <= 0) break;
        if (total + n > EFS_MAX) { *err = -4; break; }
        memcpy(g_data + total, resp + 20, n);
        total += n;
    }
    efs_close(fd);
    return total;
}

static int efs_write_file_once(const char *path, const uint8_t *data, size_t len, int *err);

static int efs_write_file(const char *path, const uint8_t *data, size_t len, int *err) {
    int rc = -1;
    for (int attempt = 0; attempt < 3 && rc != 0; attempt++) {
        rc = efs_write_file_once(path, data, len, err);
    }
    return rc;
}

static int efs_write_file_once(const char *path, const uint8_t *data, size_t len, int *err) {
    int fd = efs_open(path, 0101 | 01000, 0666, err);   /* O_WRONLY|O_CREAT|O_TRUNC（同 python 0o1101） */
    if (*err) {
        for (const char *q = path + 1; *q; q++)           /* 逐層建立父目錄 */
            if (*q == '/') {
                char part[512];
                size_t L = q - path;
                memcpy(part, path, L); part[L] = 0;
                efs_mkdir(part);
            }
        fd = efs_open(path, 0101 | 01000, 0666, err);
    }
    if (*err) return -1;
    size_t off = 0;
    while (off < len) {
        size_t c = len - off > 512 ? 512 : len - off;
        uint8_t body[1024], resp[256];
        put32(body, fd); put32(body + 4, off);
        memcpy(body + 8, data + off, c);
        size_t r = efs_call(5, body, 8 + c, resp);
        if (r < 20 || i32(resp + 12) != (int)c || i32(resp + 16) != 0) { efs_close(fd); *err = -2; return -1; }
        off += c;
    }
    efs_close(fd);
    return 0;
}

static int load_local(const char *path, uint8_t *buf, size_t cap, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) return -1;
    *len = fread(buf, 1, cap, f);
    fclose(f);
    return 0;
}

static int save_local(const char *path, const uint8_t *buf, size_t len) {
    FILE *f = fopen(path, "wb");
    if (!f) return -1;
    size_t w = fwrite(buf, 1, len, f);
    fclose(f);
    return w == len ? 0 : -1;
}


static void do_line(char *line) {
    char op[16], a[512], b[512];
    int n = sscanf(line, "%15s %511s %511s", op, a, b);
    if (n < 1) return;
    if (!strcmp(op, "backup") && n == 3) {
        int err = 0;
        size_t len = efs_read_file(a, &err);
        if (err) {
            char mark[600];
            snprintf(mark, sizeof(mark), "%s.absent", b);
            save_local(mark, (const uint8_t *)"", 0);
            fprintf(g_out, "OK backup-absent %s (err=%d)\n", a, err);
        } else if (save_local(b, g_data, len) == 0) {
            fprintf(g_out, "OK backup %s len=%zu\n", a, len);
        } else {
            fprintf(g_out, "FAIL backup-save %s\n", a);
        }
    } else if (!strcmp(op, "write") && n == 3) {
        static uint8_t in[EFS_MAX];
        size_t len = 0;
        if (load_local(b, in, sizeof(in), &len)) { fprintf(g_out, "FAIL write-local %s\n", b); return; }
        int err = 0;
        int rc = efs_write_file(a, in, len, &err);
        if (rc) { fprintf(g_out, "FAIL write %s err=%d\n", a, err); return; }
        size_t back = efs_read_file(a, &err);
        if (err || back != len || memcmp(g_data, in, len) != 0)
            fprintf(g_out, "FAIL verify-after-write %s err=%d len=%zu/%zu\n", a, err, back, len);
        else
            fprintf(g_out, "OK write %s len=%zu\n", a, len);
    } else if (!strcmp(op, "verify") && n == 3) {
        static uint8_t in[EFS_MAX];
        size_t len = 0;
        int err = 0;
        if (load_local(b, in, sizeof(in), &len)) { fprintf(g_out, "FAIL verify-local %s\n", b); return; }
        size_t back = efs_read_file(a, &err);
        if (err || back != len || memcmp(g_data, in, len) != 0)
            fprintf(g_out, "FAIL verify %s err=%d len=%zu/%zu\n", a, err, back, len);
        else
            fprintf(g_out, "OK verify %s len=%zu\n", a, len);
    } else if (!strcmp(op, "unlink") && n == 2) {
        int r = efs_unlink(a);
        fprintf(g_out, "OK unlink %s rc=%d\n", a, r);
    } else if (!strcmp(op, "probe")) {
        /* 診斷：送出無害的 EFS opendir 空路徑請求，印出原始回應 */
        uint8_t req[4] = { 0x4B, 0x13, 0x0B, 0x00 }, fr[32], pk[40];
        size_t n = hdlc_encode(req, 4, fr);
        put32(pk, USER_SPACE_DATA_TYPE);
        memcpy(pk + 4, fr, n);
        ssize_t w = write(g_fd, pk, n + 4);
        fprintf(g_out, "probe write=%zd\n", w);
        for (int k = 0; k < 4; k++) {
            static uint8_t rb[16384];
            ssize_t r = read_timed(rb, sizeof(rb), 1);
            fprintf(g_out, "probe read[%d]=%zd:", k, r);
            for (ssize_t q = 0; q < r && q < 24; q++) fprintf(g_out, " %02x", rb[q]);
            fprintf(g_out, "\n");
        }
    } else {
        fprintf(g_out, "FAIL bad-line %s\n", line);
    }
}

void run_batch_impl(void) {
    char line[1024];
    FILE *cf = fopen(CMD_CFG, "r");
    if (!cf) return;
    g_out = fopen(OUT_PATH, "w");
    if (!g_out) { fclose(cf); return; }
    setvbuf(g_out, NULL, _IONBF, 0);
    g_fd = open(DIAG_DEV, O_RDWR);
    if (g_fd < 0) { fprintf(g_out, "FAIL open-diag\n"); fclose(cf); fclose(g_out); return; }
    if (diag_set_mode(MEMORY_DEVICE_MODE)) fprintf(g_out, "FAIL switch-memory-device\n");
    while (fgets(line, sizeof(line), cf)) {
        line[strcspn(line, "\r\n")] = 0;
        if (line[0] == 0 || line[0] == '#') continue;
        do_line(line);
    }
    fclose(cf);
    if (diag_set_mode(USB_MODE)) fprintf(g_out, "FAIL switch-usb\n");
    close(g_fd);
    fclose(g_out);
}

/* JNI 入口（不引用 jni.h：不使用 env，只跑固定路徑的批次命令）。
 * Java: com.sonymobile.customizationselector.EfsNative.runBatch() → 回傳成功執行的行數 */
int Java_com_sonymobile_customizationselector_EfsNative_runBatch(void *env, void *cls) {
    (void)env; (void)cls;
    run_batch_impl();
    return 0;
}
