package com.tma.exercises;

import javax.swing.*;

/**
 * @author Jesper Buus Nielsen
 */
public class TextInsertEvent extends MyTextEvent {

    private String text;

    public TextInsertEvent(int offset, String text) {
        super(offset);
        this.text = text;
    }

    public String getText() {
        return text;
    }

    @Override
    void perform(JTextArea area) {
        area.insert(getText(), getOffset() + getAdjustOffset());
    }

    @Override
    void undo(JTextArea area) {
        area.replaceRange(null, getOffset(), getOffset() + getText().length());
    }

    @Override
    public String toString() {
        return String.format("Insert '%s': %s", text, super.toString());
    }

    @Override
    public int getAdjustOffset(int offset) {
        if (getOffset() + getAdjustOffset() <= offset)
            return getText().length();

        return 0;
    }
}

