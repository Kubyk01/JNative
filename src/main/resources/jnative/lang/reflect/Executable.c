/* src/main/resources/jnative/lang/reflect/Executable.c */

#include <stdint.h>

/*
 * private native byte[] getTypeAnnotationBytes0();
 *
 * Returns the raw RuntimeVisibleTypeAnnotations attribute bytes for this
 * executable. The JDK contract is: return null when the executable carries
 * no type annotations. The Java-side callers (Executable.getAnnotatedReturnType0,
 * getAnnotatedParameterTypes, getAnnotatedReceiverType, getAnnotatedExceptionTypes,
 * and TypeAnnotationParser.parseTypeAnnotations) explicitly check for null and
 * substitute an empty TypeAnnotation[] in that case.
 *
 * This runtime does not retain the raw class-file attribute payload attached
 * to a Method/Constructor object — the class parser (DependencyResolver) discards
 * bytecode attributes after extracting the structural information. Consequently
 * the only semantically correct answer that the caller can act on is "no type
 * annotations", which the JDK represents as null (not an empty array).
 *
 * Returning an empty byte[] would be wrong: TypeAnnotationParser.parseTypeAnnotations
 * would attempt to parse it as a serialised RuntimeVisibleTypeAnnotations structure
 * and fail with an ArrayIndexOutOfBoundsException.
 */
void* __jnative_fn_java_lang_reflect_Executable_getTypeAnnotationBytes0____B(void* this_executable) {
    (void)this_executable;
    return (void*)0;
}