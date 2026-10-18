package ai.vigzone.chatgptconnector;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChannelGateTest {
    @Test public void restoredTabDoesNotNeedAnotherNavigationToConnect() {
        ChannelGate gate = new ChannelGate();
        gate.tabShown(); assertFalse(gate.beginRequest());
        gate.validated(true); assertTrue(gate.beginRequest());
        gate.tabShown(); assertFalse(gate.beginRequest());
    }
    @Test public void showingATabDuringNavigationWaitsForTheNewDocument() {
        ChannelGate gate = new ChannelGate();
        gate.validated(true); gate.navigationStarted(); gate.tabShown();
        assertFalse(gate.beginRequest());
        gate.navigationFinished(); assertTrue(gate.beginRequest());
    }
    @Test public void requestValidationCallbacksCannotRecursivelyRequestMoreChannels() {
        ChannelGate gate = new ChannelGate();
        gate.validated(true); gate.navigationFinished();
        assertTrue(gate.beginRequest());
        for (int i = 0; i < 1000; i++) {
            gate.validated(true);
            assertFalse(gate.beginRequest());
        }
    }
    @Test public void eitherReadinessOrderAllowsOnlyOneRequest() {
        ChannelGate gate = new ChannelGate();
        gate.navigationFinished(); assertFalse(gate.beginRequest());
        gate.validated(true); assertTrue(gate.beginRequest());
        gate.navigationFinished(); assertFalse(gate.beginRequest());
    }
    @Test public void aNewDocumentCanEstablishItsOwnChannel() {
        ChannelGate gate = new ChannelGate();
        gate.validated(true); gate.navigationFinished(); assertTrue(gate.beginRequest());
        gate.navigationStarted(); assertFalse(gate.beginRequest());
        gate.navigationFinished(); assertTrue(gate.beginRequest());
        assertFalse(gate.beginRequest());
    }
    @Test public void rejectionAndDisconnectionDoNotPretendTheChannelIsReady() {
        ChannelGate gate = new ChannelGate();
        gate.validated(true); gate.navigationFinished(); assertTrue(gate.beginRequest());
        gate.rejected(); assertTrue(gate.beginRequest());
        gate.disconnected(); assertFalse(gate.isValidated()); assertFalse(gate.beginRequest());
        gate.navigationFinished(); gate.validated(false); assertFalse(gate.beginRequest());
    }
    @Test public void stalledDocumentCanRecoverWithoutANavigationFinishedCallback() {
        ChannelGate gate=new ChannelGate();
        gate.validated(true);gate.navigationFinished();assertTrue(gate.beginRequest());
        gate.navigationStarted();assertFalse(gate.beginRequest());
        gate.recoverAfterNavigationDelay();assertTrue(gate.beginRequest());
        for(int i=0;i<1000;i++){gate.validated(true);assertFalse(gate.beginRequest());}
    }
    @Test public void delayedRecoveryNeverGrantsOriginTrust() {
        ChannelGate gate=new ChannelGate();gate.navigationStarted();
        gate.recoverAfterNavigationDelay();assertFalse(gate.beginRequest());
        gate.validated(false);assertFalse(gate.beginRequest());
    }
}
