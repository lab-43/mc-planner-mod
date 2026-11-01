package dev.lab43.mcplanner.core;

/**
 * Answer of {@code POST /api/v1/device/code} (RFC 8628 device authorization).
 *
 * @param deviceCode              secret code the mod polls with (never show it)
 * @param userCode                short code the person types / checks in the browser, e.g. "WDJB-MJHT"
 * @param verificationUri         page where the code is entered
 * @param verificationUriComplete same page with the code filled in (falls back to verificationUri)
 * @param expiresIn               seconds until the codes expire
 * @param interval                minimum seconds between polls
 */
public record DeviceCode(String deviceCode, String userCode, String verificationUri, String verificationUriComplete,
                         int expiresIn, int interval) {}
