package com.hospitalqueue.common.config;

/** Names of all Redis caches, in one place so services and CacheConfig always agree. */
public final class CacheNames {

    /** Hospital list pages and single hospitals. */
    public static final String HOSPITALS = "hospitals";

    /** The full specialty list. */
    public static final String SPECIALTIES = "specialties";

    /** Doctor discovery pages and single doctors. */
    public static final String DOCTORS = "doctors";

    private CacheNames() {
    }
}
