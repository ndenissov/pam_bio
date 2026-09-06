/*
 * pam_bio.so — PAM module for biometric authentication via PamBio daemon.
 *
 * This module communicates with the pambiod daemon over a Unix domain socket
 * using a simple length-prefixed JSON protocol. On success it returns
 * PAM_SUCCESS; on any failure (daemon unreachable, timeout, denied) it
 * returns PAM_IGNORE so the system falls back to the next auth method
 * (e.g. password).
 *
 * Build:  gcc -fPIC -Wall -Wextra -O2 -shared -o pam_bio.so pam_bio.c -lpam
 * Install: cp pam_bio.so /lib/security/
 *
 * PAM config (/etc/pam.d/sudo or sddm):
 *   auth sufficient pam_bio.so
 */

#define PAM_SM_AUTH

#include <security/pam_modules.h>
#include <security/pam_ext.h>

#include <arpa/inet.h>
#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <syslog.h>
#include <unistd.h>

#define SOCKET_PATH      "/var/run/pambio.sock"
#define AUTH_TIMEOUT_SEC  30
#define MAX_MSG_SIZE      4096

/* ──────────────────────────────────────────────
 * Length-prefixed message I/O (4-byte big-endian header)
 * ────────────────────────────────────────────── */

static int write_all(int fd, const void *buf, size_t len)
{
    const char *p = (const char *)buf;
    while (len > 0) {
        ssize_t n = write(fd, p, len);
        if (n <= 0) return -1;
        p   += n;
        len -= (size_t)n;
    }
    return 0;
}

static int read_all(int fd, void *buf, size_t len)
{
    char *p = (char *)buf;
    while (len > 0) {
        ssize_t n = read(fd, p, len);
        if (n <= 0) return -1;
        p   += n;
        len -= (size_t)n;
    }
    return 0;
}

static int send_message(int fd, const char *data, size_t len)
{
    uint32_t net_len = htonl((uint32_t)len);
    if (write_all(fd, &net_len, 4) < 0) return -1;
    if (write_all(fd, data, len)   < 0) return -1;
    return 0;
}

static int recv_message(int fd, char *buf, size_t buf_size, size_t *out_len)
{
    uint32_t net_len;
    if (read_all(fd, &net_len, 4) < 0) return -1;

    uint32_t msg_len = ntohl(net_len);
    if (msg_len >= buf_size) return -1;   /* too large */

    if (read_all(fd, buf, msg_len) < 0) return -1;
    buf[msg_len] = '\0';
    *out_len = msg_len;
    return 0;
}

/* ──────────────────────────────────────────────
 * Minimal JSON string extraction (no deps)
 *
 * Finds "key":"value" or "key": "value" and copies value to out.
 * Returns out on success, NULL on failure.
 * ────────────────────────────────────────────── */

static const char *json_get_string(const char *json,
                                   const char *key,
                                   char *out, size_t out_size)
{
    /* Build two search patterns to handle optional whitespace */
    char pat1[128], pat2[128];
    snprintf(pat1, sizeof(pat1), "\"%s\":\"",  key);
    snprintf(pat2, sizeof(pat2), "\"%s\": \"", key);

    const char *start = strstr(json, pat1);
    if (!start) start = strstr(json, pat2);
    if (!start) return NULL;

    /* Advance to the colon, then past whitespace + opening quote */
    start = strchr(start, ':');
    if (!start) return NULL;
    start++;
    while (*start == ' ' || *start == '\t') start++;
    if (*start != '"') return NULL;
    start++;

    const char *end = strchr(start, '"');
    if (!end) return NULL;

    size_t len = (size_t)(end - start);
    if (len >= out_size) len = out_size - 1;
    memcpy(out, start, len);
    out[len] = '\0';
    return out;
}

/* ──────────────────────────────────────────────
 * Sanitise a string for safe JSON embedding.
 * Only ASCII alnum + basic punctuation are kept; everything else is replaced
 * with '_'.  The output is always NUL-terminated.
 * ────────────────────────────────────────────── */

static void sanitize_for_json(const char *in, char *out, size_t out_size)
{
    size_t i = 0;
    for (; *in && i < out_size - 1; in++, i++) {
        char c = *in;
        if ((c >= 'a' && c <= 'z') ||
            (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') ||
            c == '-' || c == '_' || c == '.' || c == '@') {
            out[i] = c;
        } else {
            out[i] = '_';
        }
    }
    out[i] = '\0';
}

/* ══════════════════════════════════════════════
 * PAM entry points
 * ══════════════════════════════════════════════ */

PAM_EXTERN int
pam_sm_authenticate(pam_handle_t *pamh, int flags,
                    int argc, const char **argv)
{
    (void)flags; (void)argc; (void)argv;

    const char *raw_user    = NULL;
    const char *raw_service = NULL;
    int         fd          = -1;
    int         ret         = PAM_IGNORE;  /* safe default: fall through */

    /* ── Get user name ─────────────────────── */
    if (pam_get_user(pamh, &raw_user, NULL) != PAM_SUCCESS || !raw_user) {
        pam_syslog(pamh, LOG_ERR, "pam_bio: cannot get username");
        return PAM_IGNORE;
    }

    /* ── Get service name ──────────────────── */
    if (pam_get_item(pamh, PAM_SERVICE, (const void **)&raw_service) != PAM_SUCCESS
        || !raw_service) {
        raw_service = "unknown";
    }

    /* Sanitise both strings so they can be safely embedded in JSON */
    char user[128], service[128];
    sanitize_for_json(raw_user,    user,    sizeof(user));
    sanitize_for_json(raw_service, service, sizeof(service));

    /* ── Connect to daemon ─────────────────── */
    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        pam_syslog(pamh, LOG_WARNING,
                   "pam_bio: socket(): %s", strerror(errno));
        return PAM_IGNORE;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, SOCKET_PATH, sizeof(addr.sun_path) - 1);

    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        pam_syslog(pamh, LOG_INFO,
                   "pam_bio: daemon not reachable (%s)", strerror(errno));
        close(fd);
        return PAM_IGNORE;  /* daemon offline → fallback */
    }

    /* ── Send auth request ─────────────────── */
    char request[512];
    int req_len = snprintf(request, sizeof(request),
        "{\"action\":\"auth_request\","
         "\"user\":\"%s\","
         "\"service\":\"%s\"}",
        user, service);

    if (req_len < 0 || (size_t)req_len >= sizeof(request)) {
        pam_syslog(pamh, LOG_ERR, "pam_bio: request too long");
        close(fd);
        return PAM_IGNORE;
    }

    if (send_message(fd, request, (size_t)req_len) < 0) {
        pam_syslog(pamh, LOG_ERR,
                   "pam_bio: send failed: %s", strerror(errno));
        close(fd);
        return PAM_IGNORE;
    }

    /* ── Wait for response (with timeout) ──── */
    fd_set rfds;
    struct timeval tv;
    FD_ZERO(&rfds);
    FD_SET(fd, &rfds);
    tv.tv_sec  = AUTH_TIMEOUT_SEC;
    tv.tv_usec = 0;

    int sel = select(fd + 1, &rfds, NULL, NULL, &tv);
    if (sel < 0) {
        pam_syslog(pamh, LOG_ERR,
                   "pam_bio: select(): %s", strerror(errno));
        close(fd);
        return PAM_IGNORE;
    }
    if (sel == 0) {
        pam_syslog(pamh, LOG_INFO,
                   "pam_bio: timeout waiting for biometric (%ds)", AUTH_TIMEOUT_SEC);
        close(fd);
        return PAM_IGNORE;
    }

    /* ── Read & parse response ─────────────── */
    char response[MAX_MSG_SIZE];
    size_t resp_len;
    if (recv_message(fd, response, sizeof(response), &resp_len) < 0) {
        pam_syslog(pamh, LOG_ERR, "pam_bio: recv failed");
        close(fd);
        return PAM_IGNORE;
    }
    close(fd);

    char status[64];
    if (!json_get_string(response, "status", status, sizeof(status))) {
        pam_syslog(pamh, LOG_ERR, "pam_bio: missing 'status' in response");
        return PAM_IGNORE;
    }

    if (strcmp(status, "success") == 0) {
        pam_syslog(pamh, LOG_INFO,
                   "pam_bio: auth SUCCESS for %s via %s", user, service);
        ret = PAM_SUCCESS;
    } else {
        char reason[256] = {0};
        json_get_string(response, "reason", reason, sizeof(reason));
        pam_syslog(pamh, LOG_INFO,
                   "pam_bio: auth DENIED for %s via %s (%s)",
                   user, service,
                   reason[0] ? reason : "no reason");
        ret = PAM_AUTH_ERR;
    }

    return ret;
}

PAM_EXTERN int
pam_sm_setcred(pam_handle_t *pamh, int flags,
               int argc, const char **argv)
{
    (void)pamh; (void)flags; (void)argc; (void)argv;
    return PAM_SUCCESS;
}
