package com.tma.exercises;

import javax.swing.*;

public class TextRemoveEvent extends MyTextEvent {

    private String removed;

    public TextRemoveEvent(int offset, String text) {
        super(offset);
        removed = text;
    }

    public int getLength() {
        return removed.length();
    }

    @Override
    void perform(JTextArea area) {
        area.replaceRange(null, getOffset() + getAdjustOffset(), getOffset() + getAdjustOffset() + getLength());
    }

    @Override
    void undo(JTextArea area) {
        area.insert(removed, getOffset());
    }

    @Override
    public String toString() {
        return String.format("Remove '%s': %s", removed, super.toString());
    }

    @Override
    public int getAdjustOffset(int offset) {
        // If this delete happens before, then we need to move
        // the other offset backwards
        if (getOffset() + getAdjustOffset() < offset)
            return -getLength();

        return 0;
    }
}
