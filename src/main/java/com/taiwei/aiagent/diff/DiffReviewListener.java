package com.taiwei.aiagent.diff;

import java.util.EventListener;

/**
 * Listener interface for diff review events.
 * Implementations are notified when diffs are added, index changes, or all diffs are cleared.
 */
public interface DiffReviewListener extends EventListener {

    void onDiffAdded(DiffEntry entry);

    void onIndexChanged(int newIndex);

    void onAllCleared();
}
