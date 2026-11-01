package dev.lab43.mcplanner.core;

/**
 * One answer of {@code POST /api/v1/device/token}.
 *
 * @param kind        what happened
 * @param accessToken the device token (APPROVED only)
 * @param userId      Lab43 user id (APPROVED only)
 * @param userName    display name (APPROVED only)
 * @param interval    SLOW_DOWN: the new interval in seconds if the hub sent one, else 0
 */
public record TokenResult(Kind kind, String accessToken, String userId, String userName, int interval) {

    public enum Kind {
        /** authorization_pending: keep polling */
        PENDING,
        /** slow_down: keep polling, 5 s slower */
        SLOW_DOWN,
        /** signed in: accessToken / userId / userName are set */
        APPROVED,
        /** access_denied: the person said no */
        DENIED,
        /** expired_token: start again */
        EXPIRED,
        /** invalid_grant (or another OAuth error): the device code is unknown / used */
        INVALID
    }

    static TokenResult of(Kind kind) {
        return new TokenResult(kind, null, null, null, 0);
    }
}
