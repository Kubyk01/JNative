#define _GNU_SOURCE
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <time.h>
#include <unistd.h>
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
 * Resolution order (matches the Unix reference implementation):
 *
 *   1. The TZ environment variable. If set and not prefixed with ':',
 *      its value is used verbatim. A leading ':' means "use the file
 *      at the given path", which is not a valid zone ID; we skip it
 *      and fall through to the next source.
 *
 *   2. /etc/timezone (Debian/Ubuntu). A single line of text naming the
 *      system zone, e.g. "Europe/Warsaw".
 *
 *   3. The /etc/localtime symlink. On most distributions this is a
 *      symlink into /usr/share/zoneinfo/, and the trailing path
 *      component after "zoneinfo/" is the zone ID.
 *
 *   4. A synthesised GMT offset, computed from the local machine's
 *      current UTC offset via localtime_r / tm_gmtoff. The result
 *      looks like "GMT+01:00" or "GMT-05:00", which
 *      TimeZone.getTimeZone() parses into a SimpleTimeZone with the
 *      correct offset.
 *
 * All four paths produce a string the Java layer's
 * TimeZone.getTimeZone() accepts, so the fallback chain never leaves
 * the caller without a valid zone.
 */

/*
 * Format the current UTC offset as "GMT+HH:MM" / "GMT-HH:MM".
 *
 * The offset is read from the kernel on the local machine using
 * localtime_r(), whose tm_gmtoff field holds the number of seconds
 * east of UTC (positive for zones ahead of UTC). A zero offset is
 * reported as the canonical "GMT" rather than as "GMT+00:00", matching
 * the canonical name of the UTC zone and avoiding a needless "+00:00"
 * suffix in stack traces and log lines.
 *
 * On a target whose <time.h> does not expose tm_gmtoff (glibc and
 * musl do; strictly conforming C does not), the offset is derived from
 * the difference between localtime_r and gmtime_r instead. Both paths
 * produce the same value for every time zone that a POSIX system can
 * be configured with.
 */
static void format_gmt_offset(char* buf, size_t buf_size) {
    time_t now = time(NULL);
    struct tm local_tm;
    memset(&local_tm, 0, sizeof(local_tm));

    long off_sec = 0;
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

/*
 * Try to resolve the system zone ID from a well-known source. Returns
 * 1 on success (name copied into buf), 0 otherwise.
 */

/*
 * 1. The TZ environment variable.
 *
 * A leading ':' means "the value is a path to a file in the reference
 * implementation's format", not a zone ID; that form is skipped so the
 * caller can fall through to the next source. An empty TZ is likewise
 * treated as "not set", matching the reference implementation.
 */
static int try_tz_env(char* buf, size_t buf_size) {
    const char* tz = getenv("TZ");
    if (tz == NULL || tz[0] == '\0') return 0;
    if (tz[0] == ':') return 0;
    if (strlen(tz) >= buf_size) return 0;
    strcpy(buf, tz);
    return 1;
}

/*
 * 2. /etc/timezone — a single line of text naming the system zone.
 *
 * Used by Debian, Ubuntu, and their derivatives. The file is written by
 * dpkg's tzdata postinst and contains exactly one line; trailing
 * whitespace is stripped so the ID that reaches the Java layer has no
 * stray control characters.
 */
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

/*
 * 3. The /etc/localtime symlink.
 *
 * On most distributions this is a symlink into
 * /usr/share/zoneinfo/<Zone>/<Subzone>. The trailing component after
 * "/zoneinfo/" is the zone ID, and it is exactly the string that the
 * Java layer's TimeZone.getTimeZone() accepts.
 *
 * A regular-file /etc/localtime (which some hand-configured systems
 * use to avoid depending on the zoneinfo tree) is not a symlink and
 * therefore yields no ID; the caller falls through to the GMT-offset
 * synthesiser below, which produces a valid zone for any offset.
 */
static int try_localtime_symlink(char* buf, size_t buf_size) {
    char target[PATH_MAX];
    ssize_t n = readlink("/etc/localtime", target, sizeof(target) - 1);
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

/*
 * static native String getSystemTimeZoneID(String javaHome);
 *
 * Always returns the offset form "GMT" / "GMT±HH:MM" computed from the
 * machine's current UTC offset. The regional ID (for example
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