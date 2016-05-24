package com.tma.exercises;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

public class Peer {
    private Socket socket;
    private ObjectInputStream objectInputStream;
    private ObjectOutputStream objectOutputStream;
    private int index;

    public Peer(Socket socket) throws IOException {
        this.socket = socket;
        objectOutputStream = new ObjectOutputStream(socket.getOutputStream());
    }

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public Object receive() throws IOException {
        if (objectInputStream == null)
            objectInputStream = new ObjectInputStream(socket.getInputStream());

        try {
            return objectInputStream.readObject();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            throw new RuntimeException("Cannot happen");
        }
    }

    public void send(Object obj) throws IOException {
        objectOutputStream.writeObject(obj);
    }

    public void close() throws IOException {
        if (objectInputStream != null)
            objectInputStream.close();

        objectOutputStream.close();
        socket.close();
    }

    public Socket getSocket() {
        return socket;
    }
}
