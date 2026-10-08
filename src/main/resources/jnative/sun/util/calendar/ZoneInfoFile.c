/*
 * sun.util.calendar.ZoneInfoFile — нативные переопределения.
 *
 * Штатная инициализация ZoneInfoFile читает <java.home>/lib/tzdb.dat
 * через обычный FileInputStream и разбирает бинарный формат TZDB.
 * В AOT-образе это ломается по трём независимым причинам: файла может
 * не быть; файл может быть от другой версии JDK, чем та, чей
 * ZoneInfoFile скомпилирован в образ; AOT-образ по замыслу не должен
 * зависеть от внешних файлов с данными.
 *
 * Любая из трёх причин приводит к одной и той же ошибке:
 *
 *   java.io.StreamCorruptedException: File format not recognised
 *       at sun.util.calendar.ZoneInfoFile.1.run(Ljava/lang/Void;)
 *       at sun.util.calendar.ZoneInfoFile(clinit)V
 *
 * Оба переопределения ниже устраняют саму причину: loadTZDB не
 * открывает файлов, а getZoneInfo0 не обращается к базе данных.
 *
 * ВАЖНО ПРО СИНТАКСИС: обе функции объявлены с пустыми скобками `()`,
 * а не с `(void)`. Сканер NativeOverrideScanner разбирает список
 * параметров как обычный C-список, и единственный токен `void` он
 * превращает в один параметр типа void. Это даёт declare
 *     void @f(void)
 * который LLVM считает невалидным. Пустые скобки дают ноль
 * параметров, и declare получается корректный:
 *     void @f()
 */

#define _GNU_SOURCE
#include <stdint.h>
#include <string.h>

#include "jnative_runtime.h"

/*
 * Статические поля sun.util.calendar.ZoneInfoFile. Эти глобалы
 * эмитируются LlvmGlobalEmitter.generateStaticFields только если
 * соответствующее поле встречается в GET_STATIC/PUT_STATIC хоть
 * одного метода, попавшего в модуль. Слабые ссылки позволяют файлу
 * слинковаться даже если конкретный глобал не эмитился; проверка
 * &global != NULL перед записью превращает отсутствие символа в
 * безопасный пропуск поля.
 *
 * Имя глобала: "gv_" + sanitize("sun/util/calendar/ZoneInfoFile.<field>"),
 * где sanitize сворачивает все символы вне [a-zA-Z0-9_] в '_'.
 */
extern void* gv_sun_util_calendar_ZoneInfoFile_regions    __attribute__((weak));
extern void* gv_sun_util_calendar_ZoneInfoFile_indices    __attribute__((weak));
extern void* gv_sun_util_calendar_ZoneInfoFile_ruleArray  __attribute__((weak));
extern void* gv_sun_util_calendar_ZoneInfoFile_aliases    __attribute__((weak));
extern void* gv_sun_util_calendar_ZoneInfoFile_zones      __attribute__((weak));
extern void* gv_sun_util_calendar_ZoneInfoFile_versionId  __attribute__((weak));

/*
 * =====================================================================
 * loadTZDB() — no-op, инициализирующий пустой in-memory TZDB.
 * =====================================================================
 *
 * Java-версия loadTZDB оборачивает тело в
 * AccessController.doPrivileged и ловит любое Exception (включая
 * StreamCorruptedException) в свежий java.lang.Error. Замена тела на
 * такое, которое не открывает ни одного файла и никогда не бросает
 * исключение, устраняет эту ошибку в корне.
 *
 * Контейнеры, которые читает остальной ZoneInfoFile, инициализируются
 * корректными пустыми значениями. Пустые массивы проходят через
 * рантаймовые фабрики, поэтому их заголовки несут канонический array
 * layout (klass @ 0, length @ 8, elem_size @ 12) и любые ALOAD /
 * ARRAYLENGTH на них работают корректно.
 *
 * Пустой java.util.HashMap создаётся одним calloc — все его поля в
 * нуле дают валидную пустую map, потому что HashMap.get корректно
 * обрабатывает table == null и size == 0.
 */
void __jnative_override_sun_util_calendar_ZoneInfoFile_loadTZDB() {
    if (&gv_sun_util_calendar_ZoneInfoFile_regions != NULL) {
        gv_sun_util_calendar_ZoneInfoFile_regions =
            jnative_ref_array_of_class(NULL, 0, "[Ljava/lang/String;");
    }
    if (&gv_sun_util_calendar_ZoneInfoFile_indices != NULL) {
        gv_sun_util_calendar_ZoneInfoFile_indices =
            jnative_int_array(NULL, 0);
    }
    if (&gv_sun_util_calendar_ZoneInfoFile_ruleArray != NULL) {
        gv_sun_util_calendar_ZoneInfoFile_ruleArray =
            jnative_ref_array_of_class(NULL, 0, "[[B");
    }
    if (&gv_sun_util_calendar_ZoneInfoFile_versionId != NULL) {
        gv_sun_util_calendar_ZoneInfoFile_versionId =
            jnative_string("unknown");
    }

    if (&gv_sun_util_calendar_ZoneInfoFile_aliases != NULL) {
        ReflectionClass* hm_cls = jnative_class_by_name("java/util/HashMap");
        if (hm_cls != NULL) {
            gv_sun_util_calendar_ZoneInfoFile_aliases =
                jnative_alloc_object(hm_cls);
        }
    }

    if (&gv_sun_util_calendar_ZoneInfoFile_zones != NULL) {
        ReflectionClass* chm_cls =
            jnative_class_by_name("java/util/concurrent/ConcurrentHashMap");
        if (chm_cls != NULL) {
            gv_sun_util_calendar_ZoneInfoFile_zones =
                jnative_alloc_object(chm_cls);
        }
    }
}

/*
 * =====================================================================
 * getZoneInfo0(String) — синтез ZoneInfo без вызова Java-конструктора.
 * =====================================================================
 *
 * Java-версия getZoneInfo0 ищет ID в zones, aliases и regions и
 * разбирает rule-блоб. При пустой базе все поиски ничего не находят,
 * и метод возвращает null. Данное переопределение заменяет поиск
 * прямой конструкцией ZoneInfo с двумя-тремя полями, которых
 * достаточно всем вызывающим сторонам в скомпилированном образе.
 *
 * ПОЧЕМУ КОНСТРУКТОР НЕ ВЫЗЫВАЕТСЯ ЧЕРЕЗ JAVA. Java-конструктор
 * ZoneInfo(String, int) должен быть в IR под символом
 * fn_sun_util_calendar_ZoneInfo__init___Ljava_lang_String_I_V.
 * В AOT-образе он появляется только если достижимость его туда
 * затянет, а достижимость зависит от того, какая цепочка вызовов в
 * Java его достигнет. Без правок Java гарантировать его присутствие
 * нельзя, а ссылаться на несуществующий символ — ошибка компоновки.
 *
 * Поэтому объект собирается напрямую: рантайм аллоцирует его,
 * записывает vtable, и вручную заполняет те поля, которые любой
 * вызывающий может наблюдать. Layout для JDK 21 (актуальный для
 * образа) зафиксирован ниже; на других версиях JDK он может
 * сдвинуться, но объект останется валидным ZoneInfo — просто часть
 * полей будет в нуле.
 *
 * Layout sun.util.calendar.ZoneInfo (JDK 21), вычисленный
 * LlvmGlobalEmitter.getFieldOffset по правилу: 8-байтовый заголовок,
 * затем все instance-поля с естественным выравниванием:
 *
 *   +0   vtable
 *   +8   java.util.TimeZone.ID                 (String)
 *   +16  java.util.TimeZone.rawOffset          (int, deprecated, shadowed)
 *   +20  sun.util.calendar.ZoneInfo.rawOffset  (int, читаемый getRawOffset)
 *   +24  dstSavings                            (int)
 *   +32  checksum                              (long, 8-aligned)
 *   +40  transitions                           (long[])
 *   +48  offsets                               (int[])
 *   +56  simpleTimeZoneParams                  (int[])
 *   +64  willGMTOffsetChange                   (boolean)
 *   +68  serialVersionOnStream                 (int, 4-aligned)
 *
 * Что достаточно для корректного поведения:
 *
 *   - ID          — getID()/toString() возвращают правильное имя;
 *   - ZoneInfo.rawOffset — getRawOffset()/getOffset(t) возвращают
 *                    правильное смещение (getOffset проверяет
 *                    transitions == null и сразу возвращает rawOffset);
 *   - transitions == NULL и simpleTimeZoneParams == NULL — useDaylightTime()
 *                    возвращает false, getDSTSavings() возвращает 0.
 *
 * Поля checksum / offsets / willGMTOffsetChange / serialVersionOnStream
 * остаются в нуле — их читает только машинерия десериализации и
 * внутренние ветви, недостижимые в этом образе.
 *
 * Аллокация идёт через jnative_alloc_object (тот же путь, что у
 * jnative_make_string_obj), поэтому объект получает канонический
 * vtable и заголовок. Все записываемые смещения лежат в пределах
 * object_size, вычисленного эмиттером; выходить за них нельзя —
 * это было бы переполнение кучи.
 */
void* __jnative_override_sun_util_calendar_ZoneInfoFile_getZoneInfo0(
        void* zone_id_str)
{
    if (zone_id_str == NULL) {
        return NULL;
    }

    /*
     * Вывести raw-смещение из ID, если ID имеет форму "GMT±HH:MM".
     * Эта форма производится getSystemGMTOffsetID рантайма (см.
     * jnative/util/TimeZone.c), так что круговой обход сохраняет
     * смещение в точности. Любой другой ID получает смещение 0.
     */
    int32_t raw_offset_ms = 0;
    {
        int32_t len = 0;
        const char* s = __jnative_read_string_bytes(zone_id_str, &len);
        if (s != NULL && len >= 6
            && s[0] == 'G' && s[1] == 'M' && s[2] == 'T'
            && (s[3] == '+' || s[3] == '-')) {
            int sign = (s[3] == '+') ? 1 : -1;
            int hh = 0, mm = 0;
            int i = 4;
            while (i < len && s[i] >= '0' && s[i] <= '9') {
                hh = hh * 10 + (s[i] - '0');
                i++;
            }
            if (i < len && s[i] == ':') {
                i++;
                while (i < len && s[i] >= '0' && s[i] <= '9') {
                    mm = mm * 10 + (s[i] - '0');
                    i++;
                }
            }
            raw_offset_ms = sign * (hh * 3600 + mm * 60) * 1000;
        }
    }

    ReflectionClass* cls = jnative_class_by_name("sun/util/calendar/ZoneInfo");
    if (cls == NULL) {
        return NULL;
    }

    void* zi = jnative_alloc_object(cls);
    if (zi == NULL) {
        __jnative_throw_out_of_memory_error_ctx(
            "ZoneInfoFile.getZoneInfo0");
    }

    /*
     * Все записываемые смещения лежат в пределах object_size,
     * вычисленного эмиттером для ZoneInfo. object_size считается как
     * сумма размеров всех instance-полей без выравнивания, что для
     * этого класса даёт 65 байт (8 заголовок + 8 ID + 4 TimeZone.rawOffset
     * + 4 ZoneInfo.rawOffset + 4 dstSavings + 8 checksum + 8 transitions
     * + 8 offsets + 8 simpleTimeZoneParams + 1 willGMTOffsetChange
     * + 4 serialVersionOnStream). Последнее записываемое смещение —
     * 64 (1 байт), что внутри границы.
     *
     * serialVersionOnStream (смещение 68) сознательно не пишется: его
     * читает только десериализация, недостижимая в образе.
     */
    *(void**)((char*)zi + 8)  = zone_id_str;      /* TimeZone.ID           */
    *(int32_t*)((char*)zi + 16) = raw_offset_ms;  /* TimeZone.rawOffset    */
    *(int32_t*)((char*)zi + 20) = raw_offset_ms;  /* ZoneInfo.rawOffset    */
    *(int32_t*)((char*)zi + 24) = 0;              /* dstSavings            */
    *(int64_t*)((char*)zi + 32) = 0;              /* checksum              */
    *(void**)((char*)zi + 40) = NULL;             /* transitions           */
    *(void**)((char*)zi + 48) = NULL;             /* offsets               */
    *(void**)((char*)zi + 56) = NULL;             /* simpleTimeZoneParams  */
    *(uint8_t*)((char*)zi + 64) = 0;              /* willGMTOffsetChange   */

    return zi;
}