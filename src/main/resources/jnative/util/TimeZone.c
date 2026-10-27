#define _GNU_SOURCE
#include "jnative_platform.h"
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <limits.h>
#include <sys/stat.h>

#include "jnative_runtime.h"

/*
 * java.util.TimeZone native methods.
 *
 * The Java layer's TimeZone.setDefaultZone() / getDefaultRef() chain
 * reaches these two natives when the user has not supplied an explicit
 * time zone (via the user.timezone system property or an explicit
 * setDefault call). The reference JDK consults the host OS's
 * configured time zone — either the TZ environment variable, or the
 * /etc/localtime symlink, or the platform's own time-zone database —
 * and returns a canonical ID that the Java layer then resolves
 * through TimeZone.getTimeZone(id).
 *
 * ------------------------------------------------------------------------
 * What this runtime returns
 * ------------------------------------------------------------------------
 *
 * Both natives always return an OFFSET form ("GMT" / "GMT±HH:MM") for
 * the current machine's UTC offset, never a regional ID. The regional
 * form is deliberately avoided because resolving it would require
 * ZoneId.of("Europe/Warsaw", true) -> ZoneRulesProvider.getRules ->
 * TzdbZoneRulesProvider -> reading tzdb.dat, and the TZDB parser
 * fails on this runtime with an unhandled UTFDataFormatException (see
 * the report). The offset form instead resolves through
 * ZoneId.of("GMT+01:00", true) -> ZoneOffset.of -> ZoneOffset, with no
 * TZDB involved at any step.
 *
 * The user can still request a specific offset through TZ: if the
 * variable holds an offset form (GMT/UTC/UT with or without a numeric
 * suffix), it is used verbatim. Regional values (non-offset forms) are
 * ignored, because they could not be resolved without TZDB anyway.
 *
 * ------------------------------------------------------------------------
 * What changed relative to the previous revision
 * ------------------------------------------------------------------------
 *
 * The previous revision computed the current UTC offset with
 * localtime_r and (in the strict-POSIX fallback) gmtime_r. Neither
 * function exists in MinGW: the CRT ships the C11 *localtime_s /
 * gmtime_s with a reversed argument order, and under the *identical*
 * names there is no POSIX form at all. The MinGW build therefore
 * stopped with "call to undeclared function 'localtime_r'" and
 * "call to undeclared function 'gmtime_r'".
 *
 * The Windows branch now uses GetTimeZoneInformation, which returns
 * the standard bias and the daylight bias for the current moment.
 * That is the same API HotSpot's java_props_md.c uses on Windows, and
 * it is the only correct way to read the current offset on that
 * platform — a plain bias without the daylight adjustment would be
 * wrong for the whole of the summer half of the year.
 *
 * The dead helpers try_tz_env / try_etc_timezone / try_localtime_symlink
 * were documented fallbacks that the current implementation of
 * getSystemTimeZoneID never actually invokes (it returns the offset
 * form directly, per the rationale above). They are now guarded with
 * #if !defined(_WIN32) so the Windows build carries no dead code that
 * would reference POSIX-only APIs.
 *
 * ------------------------------------------------------------------------
 * POSIX specifics preserved in this revision
 * ------------------------------------------------------------------------
 *
 * getSystemTimeZoneID does not call try_tz_env even though the
 * helper exists; the TZ check is inlined because it needs to
 * distinguish offset forms (returned verbatim) from regional forms
 * (rejected, then the offset fallback is used). The behaviour is
 * unchanged from the previous revision; only the platform split for
 * format_gmt_offset changed.
 */

/* ============================================================================
 *  format_gmt_offset — writes the machine's current UTC offset as
 *                      "GMT" / "GMT±HH:MM" into buf.
 *
 *  A zero offset is reported as the canonical "GMT", matching the
 *  canonical name of the UTC zone and avoiding a needless "+00:00"
 *  suffix in stack traces and log lines.
 *
 *  POSIX: reads tm_gmtoff (glibc / musl / BSD / macOS extension) from
 *  localtime_r's struct tm, with a fallback to gmtime_r for strict-
 *  POSIX targets whose <time.h> does not expose tm_gmtoff. Both paths
 *  produce the same value for every time zone the system can be
 *  configured with.
 *
 *  Windows: reads the current standard bias (and, if daylight saving
 *  is in effect, the additional daylight bias) from
 *  GetTimeZoneInformation. Windows measures bias in minutes with the
 *  convention "UTC = local time + bias", so the sign of the resulting
 *  seconds-east-of-UTC is reversed relative to POSIX.
 * ========================================================================= */
static void format_gmt_offset(char* buf, size_t buf_size) {
    long off_sec = 0;

#if defined(_WIN32)

    TIME_ZONE_INFORMATION tzi;
    memset(&tzi, 0, sizeof(tzi));
    DWORD result = GetTimeZoneInformation(&tzi);

    if (result == TIME_ZONE_ID_INVALID) {
        /* The API failed (only possible on an unsupported Windows
         * version, which this runtime does not target). Report UTC
         * rather than an arbitrary offset. */
        snprintf(buf, buf_size, "GMT");
        return;
    }

    LONG total_bias_minutes = tzi.Bias;
    if (result == TIME_ZONE_ID_DAYLIGHT) {
        total_bias_minutes += tzi.DaylightBias;
    } else if (result == TIME_ZONE_ID_STANDARD) {
        total_bias_minutes += tzi.StandardBias;
    }
    /* Windows measures bias in minutes with the sign convention
     * "UTC = local + bias", i.e. positive bias means WEST of UTC.
     * Negate to get seconds EAST of UTC, matching the value
     * tm_gmtoff produces on POSIX. */
    off_sec = -(long)total_bias_minutes * 60L;

#else /* !_WIN32 — POSIX */

    time_t now = time(NULL);
    struct tm local_tm;
    memset(&local_tm, 0, sizeof(local_tm));

    if (localtime_r(&now, &local_tm) != NULL) {
#if defined(__USE_MISC) || defined(__USE_BSD) || defined(__APPLE__)
        off_sec = (long)local_tm.tm_gmtoff;
#else
        struct tm utc_tm;
        memset(&utc_tm, 0, sizeof(utc_tm));
        if (gmtime_r(&now, &utc_tm) != NULL) {
            long local_secs = (long)local_tm.tm_hour * 3600
                            + (long)local_tm.tm_min  * 60
                            + (long)local_tm.tm_sec;
            long utc_secs   = (long)utc_tm.tm_hour  * 3600
                            + (long)utc_tm.tm_min   * 60
                            + (long)utc_tm.tm_sec;
            long day_diff   = (long)local_tm.tm_yday - (long)utc_tm.tm_yday;
            off_sec = (local_secs - utc_secs) + day_diff * 86400L;
        }
#endif
    }

#endif /* _WIN32 */

    if (off_sec == 0) {
        snprintf(buf, buf_size, "GMT");
        return;
    }

    char sign = (off_sec > 0) ? '+' : '-';
    long abs_sec = off_sec < 0 ? -off_sec : off_sec;
    long hh = abs_sec / 3600;
    long mm = (abs_sec % 3600) / 60;

    snprintf(buf, buf_size, "GMT%c%02ld:%02ld", sign, hh, mm);
}

/* ============================================================================
 *  Dead POSIX fallbacks.
 *
 *  These helpers document the reference JDK's full TZ resolution
 *  order (TZ -> /etc/timezone -> /etc/localtime symlink). The current
 *  implementation of getSystemTimeZoneID does not invoke them: it
 *  returns the offset form directly, per the rationale at the top of
 *  the file (the regional form cannot be resolved without a working
 *  TZDB parser). They are kept here — guarded with !_WIN32 — for a
 *  future revision that adds a TZDB parser and can legitimately
 *  consume the regional form.
 * ========================================================================= */
#if !defined(_WIN32)

/* 1. The TZ environment variable. A leading ':' means "the value is
 *    a path to a file in the reference implementation's format", not
 *    a zone ID; that form is skipped. An empty TZ is likewise
 *    treated as "not set". */
static int try_tz_env(char* buf, size_t buf_size) {
    const char* tz = getenv("TZ");
    if (tz == NULL || tz[0] == '\0') return 0;
    if (tz[0] == ':') return 0;
    if (strlen(tz) >= buf_size) return 0;
    strcpy(buf, tz);
    return 1;
}

/* 2. /etc/timezone — a single line of text naming the system zone.
 *    Used by Debian, Ubuntu, and their derivatives. */
static int try_etc_timezone(char* buf, size_t buf_size) {
    FILE* f = fopen("/etc/timezone", "r");
    if (f == NULL) return 0;

    char line[256];
    int got = 0;
    if (fgets(line, sizeof(line), f) != NULL) {
        size_t n = strlen(line);
        while (n > 0 && (line[n - 1] == '\n' || line[n - 1] == '\r'
                      || line[n - 1] == ' '  || line[n - 1] == '\t')) {
            line[--n] = '\0';
        }
        if (n > 0 && n < buf_size) {
            memcpy(buf, line, n + 1);
            got = 1;
        }
    }
    fclose(f);
    return got;
}

/* 3. The /etc/localtime symlink. On most distributions this is a
 *    symlink into /usr/share/zoneinfo/, and the trailing component
 *    after "zoneinfo/" is the zone ID. */
static int try_localtime_symlink(char* buf, size_t buf_size) {
    char target[PATH_MAX];
    ssize_t n = jn_readlink("/etc/localtime", target, sizeof(target) - 1);
    if (n <= 0) return 0;
    target[n] = '\0';

    const char* marker = "/zoneinfo/";
    const char* id = strstr(target, marker);
    if (id == NULL) return 0;
    id += strlen(marker);

    size_t id_len = strlen(id);
    if (id_len == 0 || id_len >= buf_size) return 0;
    memcpy(buf, id, id_len + 1);
    return 1;
}

#endif /* !_WIN32 */

/* ============================================================================
 *  Public natives
 * ========================================================================= */

/*
 * static native String getSystemTimeZoneID(String javaHome);
 *
 * Always returns the offset form "GMT" / "GMT±HH:MM" computed from
 * the machine's current UTC offset. The regional ID (for example
 * "Europe/Warsaw") is deliberately NOT returned:
 *
 *   1. ZoneId.of("Europe/Warsaw", true) goes into ZoneRegion.ofId,
 *      which calls ZoneRulesProvider.getRules ->
 *      TzdbZoneRulesProvider -> reading tzdb.dat.
 *
 *   2. On this build reading tzdb.dat fails with
 *      UTFDataFormatException (see the report), and the
 *      catch (Exception) in the TzdbZoneRulesProvider constructor does
 *      not fire because of the known exception-matching defect. The
 *      exception escapes as unhandled.
 *
 *   3. ZoneId.of("GMT+01:00", true) resolves through ofWithPrefix ->
 *      ZoneOffset.of -> ZoneOffset with no TZDB involved.
 *
 * The user can still request a specific offset through TZ: if the
 * variable is set and holds an offset form (GMT/UTC/UT with or without
 * a numeric offset), it is used verbatim. Regional values (non-offset
 * forms) are ignored: they could not be resolved without TZDB anyway.
 *
 * This matches the behaviour of getSystemGMTOffsetID() below — both
 * functions return offset forms and need no TZDB.
 */
void* __jnative_fn_java_util_TimeZone_getSystemTimeZoneID__Ljava_lang_String__Ljava_lang_String_(
        void* java_home_str)
{
    (void)java_home_str;

    /*
     * Explicit offset forms from TZ. The check is strict: only values
     * that ZoneId.of resolves without touching ZoneRegion.ofId.
     * Bare "UTC", "GMT", "UT" yield ZoneOffset.UTC; forms with a
     * numeric suffix resolve through ZoneOffset.of(suffix).
     */
    const char* tz = getenv("TZ");
    if (tz != NULL && tz[0] != '\0' && tz[0] != ':') {
        int is_offset_form =
            (strcmp(tz, "UTC") == 0) ||
            (strcmp(tz, "GMT") == 0) ||
            (strcmp(tz, "UT")  == 0) ||
            (strncmp(tz, "GMT+", 4) == 0) ||
            (strncmp(tz, "GMT-", 4) == 0) ||
            (strncmp(tz, "UTC+", 4) == 0) ||
            (strncmp(tz, "UTC-", 4) == 0) ||
            (strncmp(tz, "UT+",  3) == 0) ||
            (strncmp(tz, "UT-",  3) == 0);

        if (is_offset_form) {
            return jnative_string(tz);
        }
        /* Regional TZ — ignored; an offset form is returned below. */
    }

    /*
     * Always return an offset form. getSystemGMTOffsetID() below does
     * the same, so both functions stay consistent.
     */
    char buf[32];
    format_gmt_offset(buf, sizeof(buf));
    return jnative_string(buf);
}

/*
 * static native String getSystemGMTOffsetID();
 *
 * Returns a synthesised zone ID of the form "GMT[+-]HH:MM" that
 * reflects the local machine's current UTC offset. The Java caller
 * uses this when the canonical ID returned by getSystemTimeZoneID()
 * is null or cannot be resolved — the offset form is always parseable
 * and never fails.
 *
 * The value is recomputed on every call rather than cached, matching
 * the reference implementation: DST transitions change the offset, and
 * the Java layer is free to call this after such a transition has
 * occurred.
 */
void* __jnative_fn_java_util_TimeZone_getSystemGMTOffsetID___Ljava_lang_String_(void)
{
    char buf[32];
    format_gmt_offset(buf, sizeof(buf));
    return jnative_string(buf);
}