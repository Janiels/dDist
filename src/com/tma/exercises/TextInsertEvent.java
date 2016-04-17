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
        area.insert(getText(), getOffset());
    }
}

