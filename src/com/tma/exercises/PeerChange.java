package com.tma.exercises;

import java.io.Serializable;

public class PeerChange implements Serializable {
    private boolean connected;
    private String endPoint;

    public PeerChange(boolean connected, String ip) {
        this.connected = connected;
        this.endPoint = ip;
    }

    public boolean isConnected() {
        return connected;
    }

    public String getEndPoint() {
        return endPoint;
    }
}
