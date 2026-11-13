package com.kooo.evcam.browser;

/**
 * The Netflix Android app. 7xDash does not open it; it only knows its package name so that
 * {@link NetflixGuard} can hide its picture while the car is driving.
 */
public final class NetflixApp {

    public static final String PACKAGE = "com.netflix.mediaclient";

    private NetflixApp() {
    }
}
