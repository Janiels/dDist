package com.tma.exercises;

import javax.swing.*;

public class TextRemoveEvent extends MyTextEvent {

    private int length;

    public TextRemoveEvent(int offset, int length) {
        super(offset);
        this.length = length;
    }

    public int getLength() {
        return length;
    }

    @Override
    void perform(JTextArea area) {
        area.replaceRange(null, getOffset(), getOffset() + getLength());
    }
}
