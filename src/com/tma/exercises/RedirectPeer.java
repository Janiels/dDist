package com.tma.exercises;

import java.io.Serializable;

/**
 * Created by Jonas le Fevre on 24-05-2016.
 */
public class RedirectPeer implements Serializable {
    private boolean redirect;
    private String ipAddress;
    private int port;


    public RedirectPeer(boolean shouldRedirect, String target, int port) {
        this.redirect = shouldRedirect;
        this.ipAddress = target;
        this.port = port;
    }

    public boolean shouldRedirect() {
        return redirect;
    }

    public String getIpAddress() {
        return ipAddress;
    }


    public int getPort() {
        return port;
    }
}
