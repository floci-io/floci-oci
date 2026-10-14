package io.floci.oci.services.identity;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an {@link IdentityController} write that {@link HomeRegionWriteFilter} lets through in
 * every region. Use it only for an operation OCI does not tie to the home region.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AnyRegionWrite {
}
