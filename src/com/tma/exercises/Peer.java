package com.tma.exercises;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class Peer {
    private Socket socket;
    private ObjectOutputStream objectOutputStream;

    public Peer(Socket socket) throws IOException {
        this.socket = socket;
        objectOutputStream = new ObjectOutputStream(socket.getOutputStream());
    }

    public void send(MyTextEvent event) throws IOException {
        objectOutputStream.writeObject(event);
    }

    public void close() throws IOException {
        objectOutputStream.close();
        socket.close();
    }
}
