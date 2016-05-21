package com.tma.exercises;

import javax.swing.*;

public class TextRemoveEvent extends MyTextEvent {

    private int length;
    private String removed;

    public TextRemoveEvent(int offset, int length, String text) {
        super(offset);
        this.length = length;
        removed = text;
    }

    public int getLength() {
        return length;
    }

    @Override
    void perform(JTextArea area) {
        area.replaceRange(null, getOffset(), getOffset() + getLength());
    }

    @Override
    void undo(JTextArea area) {
        area.insert(removed, getOffset());
    }

    @Override
    public String toString() {
        return String.format("Remove '%s': %s", removed, super.toString());
    }
}
