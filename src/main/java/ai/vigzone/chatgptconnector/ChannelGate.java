package ai.vigzone.chatgptconnector;

/** A postMessage request itself causes another relationship-validation callback. */
final class ChannelGate {
    private boolean validated, navigated, requested, navigating;

    void validated(boolean value) { validated = value; }
    boolean isValidated() { return validated; }
    void navigationStarted() { navigating = true; navigated = false; requested = false; }
    void navigationFinished() { navigating = false; navigated = true; }
    // Chrome can show an already loaded TWA without a new navigation callback.
    void tabShown() { if (!navigating) navigated = true; }
    void rejected() { requested = false; }
    void disconnected() { validated = false; navigated = false; requested = false; navigating = false; }

    boolean beginRequest() {
        if (!validated || !navigated || requested) return false;
        // Set before calling Chrome: validation can call back into the launcher.
        requested = true;
        return true;
    }
}
