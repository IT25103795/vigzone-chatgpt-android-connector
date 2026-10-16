package ai.vigzone.chatgptconnector;

/** A postMessage request itself causes another relationship-validation callback. */
final class ChannelGate {
    private boolean validated, navigated, requested;

    void validated(boolean value) { validated = value; }
    boolean isValidated() { return validated; }
    void navigationStarted() { navigated = false; requested = false; }
    void navigationFinished() { navigated = true; }
    void rejected() { requested = false; }
    void disconnected() { validated = false; navigationStarted(); }

    boolean beginRequest() {
        if (!validated || !navigated || requested) return false;
        // Set before calling Chrome: validation can call back into the launcher.
        requested = true;
        return true;
    }
}
