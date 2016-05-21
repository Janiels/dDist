package com.tma.exercises;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class Peer {
    private Socket socket;
    private ObjectOutputStream objectOutputStream;
    private int index;

    public Peer(Socket socket, int index) throws IOException {
        this.socket = socket;
        this.index = index;
        objectOutputStream = new ObjectOutputStream(socket.getOutputStream());
    }

    public int getIndex() {
        return index;
    }

    public void send(Object obj) throws IOException {
        objectOutputStream.writeObject(obj);
    }

    public void close() throws IOException {
        objectOutputStream.close();
        socket.close();
    }

    public Socket getSocket() {
        return socket;
    }
}
