package io.github.kubyk01.application.service.analyzer.classanalysis.annotation.cglib;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

@Slf4j
public final class CglibPregenerator {

    private static final String ENHANCER = "org.springframework.context.annotation.ConfigurationClassEnhancer";
    private static final String REFLECT_UTILS = "org.springframework.cglib.core.ReflectUtils";
    private static final String METHOD_PROXY = "org.springframework.cglib.proxy.MethodProxy";
    private static final String CONFIGURATION = "org.springframework.context.annotation.Configuration";
    private static final String ANNOTATED_ELEMENT_UTILS = "org.springframework.core.annotation.AnnotatedElementUtils";

    private CglibPregenerator() {}

    public static Map<String, byte[]> generate(List<Path> classpath, Collection<String> candidates) {
        Map<String, byte[]> generated = new LinkedHashMap<>();
        List<URL> urls = new ArrayList<>();
        for (Path p : classpath) {
            try {
                urls.add(p.toUri().toURL());
            } catch (MalformedURLException e) {
                log.warn("Skipping classpath entry {}: {}", p, e.getMessage());
            }
        }
        try (URLClassLoader loader = new URLClassLoader("jnative-cglib", urls.toArray(new URL[0]),
                ClassLoader.getPlatformClassLoader())) {
            Class<?> enhancerClass;
            try {
                enhancerClass = loader.loadClass(ENHANCER);
            } catch (ClassNotFoundException e) {
                return generated;
            }
            BiConsumer<String, byte[]> handler = (name, bytes) -> generated.put(name.replace('.', '/'), bytes);
            loader.loadClass(REFLECT_UTILS).getMethod("setGeneratedClassHandler", BiConsumer.class)
                .invoke(null, handler);

            Class<?> configuration = loader.loadClass(CONFIGURATION);
            Method mergedAttributes = loader.loadClass(ANNOTATED_ELEMENT_UTILS)
                .getMethod("getMergedAnnotationAttributes", AnnotatedElement.class, Class.class);
            Class<?> methodProxy = loader.loadClass(METHOD_PROXY);
            Method initProxy = methodProxy.getDeclaredMethod("init");
            initProxy.setAccessible(true);
            Constructor<?> ctor = enhancerClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object enhancer = ctor.newInstance();
            Method enhance = enhancerClass.getMethod("enhance", Class.class, ClassLoader.class);
            enhance.setAccessible(true);

            for (String candidate : candidates) {
                try {
                    Class<?> cls = Class.forName(candidate.replace('/', '.'), false, loader);
                    if (cls.isInterface() || cls.isAnnotation() || Modifier.isFinal(cls.getModifiers())) continue;
                    Map<?, ?> attributes = (Map<?, ?>) mergedAttributes.invoke(null, cls, configuration);
                    if (attributes == null || Boolean.FALSE.equals(attributes.get("proxyBeanMethods"))) continue;
                    Class<?> enhanced = (Class<?>) enhance.invoke(enhancer, cls, loader);
                    if (enhanced == cls) continue;
                    Class.forName(enhanced.getName(), true, loader);
                    for (Field f : enhanced.getDeclaredFields()) {
                        if (!Modifier.isStatic(f.getModifiers()) || f.getType() != methodProxy) continue;
                        f.setAccessible(true);
                        Object proxy = f.get(null);
                        if (proxy != null) initProxy.invoke(proxy);
                    }
                } catch (Throwable t) {
                    log.debug("CGLIB pre-generation skipped for {}: {}", candidate, t.toString());
                }
            }
        } catch (IOException | ReflectiveOperationException | LinkageError e) {
            log.warn("CGLIB pre-generation failed: {}", e.toString());
        }
        return generated;
    }
}
