package com.github.andreasarvidsson.eld.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Describes the Eld API of a runtime method or generated type. */
@Retention(RetentionPolicy.RUNTIME)
@Target(
    {ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR,
            ElementType.TYPE}
)
public @interface EldApi {
    boolean property() default false;

    /** JVM name with descriptor and semantic signature pairs for public generated Eld methods. */
    String[] methods() default {};

    /** JVM descriptor and semantic signature pairs for public generated Eld constructors. */
    String[] constructors() default {};
}
