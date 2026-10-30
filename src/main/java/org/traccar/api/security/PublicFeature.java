package org.traccar.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a resource method as a public feature. The method is reachable without login when the feature is enabled
 * through {@code public.enable} and {@code public.features}, and responds with 404 otherwise.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PublicFeature {

    String value();

}
