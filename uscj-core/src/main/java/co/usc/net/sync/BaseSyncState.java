package co.usc.net.sync;

import co.usc.net.MessageChannel;
import co.usc.net.messages.BodyResponseMessage;
import co.usc.scoring.EventType;
import com.google.common.annotations.VisibleForTesting;
import org.ethereum.core.BlockHeader;
import org.ethereum.core.BlockIdentifier;

import java.time.Duration;
import java.util.List;

public abstract class BaseSyncState implements SyncState {
    protected SyncConfiguration syncConfiguration;
    protected SyncEventsHandler syncEventsHandler;
    protected SyncInformation syncInformation;

    protected Duration timeElapsed;

    public BaseSyncState(SyncInformation syncInformation, SyncEventsHandler syncEventsHandler, SyncConfiguration syncConfiguration) {
        this.syncInformation = syncInformation;
        this.syncEventsHandler = syncEventsHandler;
        this.syncConfiguration = syncConfiguration;

        this.resetTimeElapsed();
    }

    protected void resetTimeElapsed() {
        timeElapsed = Duration.ZERO;
    }

    @Override
    public void tick(Duration duration) {
        timeElapsed = timeElapsed.plus(duration);
        if (timeElapsed.compareTo(syncConfiguration.getTimeoutWaitingRequest()) >= 0) {
            syncEventsHandler.onErrorSyncing(
                    "Timeout waiting requests {} from node {}", EventType.TIMEOUT_MESSAGE,
                    this.getClass(), syncInformation.getSelectedPeerId());
        }
    }

    @Override
    public void newBlockHeaders(List<BlockHeader> chunk) {
    }

    @Override
    public void newBody(BodyResponseMessage message, MessageChannel peer) {
    }

    @Override
    public void newConnectionPointData(byte[] hash) {
    }

    @Override
    public void newPeerStatus() { }

    @Override
    public void newSkeleton(List<BlockIdentifier> skeleton, MessageChannel peer) {
    }

    @Override
    public void onEnter() { }

    @Override
    public boolean isSyncing(){
        return false;
    }

    @VisibleForTesting
    public void messageSent() {
        resetTimeElapsed();
    }
}
