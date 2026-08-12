package com.taiwei.aiagent.diff;

/**
 * Represents a single diff entry tracking AI-generated code changes.
 * Core fields: filePath, oldContent, newContent, accepted.
 */
public class DiffEntry {

    private final String filePath;
    private final String oldContent;
    private final String newContent;
    private boolean accepted;

    public DiffEntry(String filePath, String oldContent, String newContent) {
        this.filePath = filePath;
        this.oldContent = oldContent;
        this.newContent = newContent;
        this.accepted = false;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getOldContent() {
        return oldContent;
    }

    public String getNewContent() {
        return newContent;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }

    @Override
    public String toString() {
        return "DiffEntry{" +
                "filePath='" + filePath + '\'' +
                ", accepted=" + accepted +
                '}';
    }
}
