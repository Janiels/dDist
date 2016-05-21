package com.tma.exercises;

import java.io.Serializable;

public class Welcome implements Serializable {
    private int index;

    public Welcome(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }
}
