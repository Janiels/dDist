package com.tma.exercises;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import javax.swing.*;
import javax.swing.text.*;
import java.net.*;
import java.util.Arrays;

public class DistributedTextEditor extends JFrame {

    private JTextArea area1 = new JTextArea(20, 120);
    private JTextField ipaddress = new JTextField("192.168.87.101");
    private JTextField portNumber = new JTextField("40615");

    private EventReplayer er;

    private JFileChooser dialog =
            new JFileChooser(System.getProperty("user.dir"));

    private String currentFile = "Untitled";
    private boolean changed = false;
    private DocumentEventCapturer dec = new DocumentEventCapturer();
    private ServerSocket serverSocket;
    private Peer server;

    public DistributedTextEditor() {
        area1.setFont(new Font("Monospaced", Font.PLAIN, 12));

        ((AbstractDocument) area1.getDocument()).setDocumentFilter(dec);

        Container content = getContentPane();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JScrollPane scroll1 =
                new JScrollPane(area1,
                        JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                        JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
        content.add(scroll1, BorderLayout.CENTER);

        content.add(ipaddress, BorderLayout.CENTER);
        content.add(portNumber, BorderLayout.CENTER);

        JMenuBar JMB = new JMenuBar();
        setJMenuBar(JMB);
        JMenu file = new JMenu("File");
        JMenu edit = new JMenu("Edit");
        JMB.add(file);
        JMB.add(edit);

        file.add(Listen);
        file.add(Connect);
        file.add(Disconnect);
        file.addSeparator();
        file.add(Save);
        file.add(SaveAs);
        file.add(Quit);

        edit.add(Copy);
        edit.add(Paste);
        edit.getItem(0).setText("Copy");
        edit.getItem(1).setText("Paste");

        Save.setEnabled(false);
        SaveAs.setEnabled(false);

        setDefaultCloseOperation(EXIT_ON_CLOSE);
        pack();
        area1.addKeyListener(k1);
        setDisconnected();
        setVisible(true);

        er = new EventReplayer(dec, area1, this);
    }

    private KeyListener k1 = new KeyAdapter() {
        public void keyPressed(KeyEvent e) {
            changed = true;
            Save.setEnabled(true);
            SaveAs.setEnabled(true);
        }
    };

    Action Listen = new AbstractAction("Listen") {
        public void actionPerformed(ActionEvent e) {
            listen(null, -1, true);
        }
    };

    private void listen(InetAddress address, int port, boolean clear) {
        if (clear)
            area1.setText("");

        String[] message = new String[1];
        if (!startListening(message, address, port)) {
            area1.setText(message[0]);
            return;
        }

        // Set the title to the end point we are listening on.
        setTitle(message[0]);

        // Reset events if we are supposed to.
        if (clear) {
            dec.clear();
        }

        // Set our index. Server is always 0.
        dec.setOurIndex(0);

        System.out.println("I am the server!");
        new Thread(() -> {
            while (true) {
                Peer peer;
                try {
                    peer = new Peer(serverSocket.accept());
                } catch (IOException ex) {
                    ex.printStackTrace();
                    break;
                }

                EventQueue.invokeLater(() -> er.addPeer(peer));
            }
        }).start();

        changed = false;
        Save.setEnabled(false);
        SaveAs.setEnabled(false);
    }

    private boolean startListening(String[] listeningMessage, InetAddress address, int port) {
        if (port == -1) {
            try {
                port = Integer.parseInt(portNumber.getText());
            } catch (NumberFormatException ex) {
                listeningMessage[0] = "Can't parse port number";
                return false;
            }
        }

        // If no address is specified then find the first possible one.
        if (address == null) {
            InetAddress[] addresses;
            try {
                addresses = InetAddress.getAllByName(InetAddress.getLocalHost().getHostName());
            } catch (UnknownHostException e) {
                listeningMessage[0] = "Could not start listening";
                return false;
            }
            Arrays.sort(addresses, (ad1, ad2) -> ad1.getHostAddress().compareTo(ad2.getHostAddress()));

            for (InetAddress potentialAddress : addresses) {
                if (!(potentialAddress instanceof Inet4Address))
                    continue;

                try {
                    serverSocket = new ServerSocket(port, 20, potentialAddress);
                    // Found one!
                    listeningMessage[0] = "Listening on " + getListenEndPoint();
                    return true;
                } catch (IOException ex) {
                }
            }
        } else {
            // Use just the IP given by caller.
            try {
                serverSocket = new ServerSocket(port, 20, address);
                listeningMessage[0] = "Listening on " + getListenEndPoint();
                return true;
            } catch (IOException ex) {
            }
        }

        listeningMessage[0] = "Could not start listening";
        return false;
    }

    Action Connect = new AbstractAction("Connect") {
        public void actionPerformed(ActionEvent e) {
            int port;
            try {
                port = Integer.parseInt(portNumber.getText());
            } catch (NumberFormatException ex) {
                area1.setText("Can't parse " + portNumber.getText());
                return;
            }

            connect(ipaddress.getText(), port);
        }
    };

    private void connect(String ip, int port) {
        area1.setText("");

        String host = ip + ":" + port;
        setTitle("Connecting to " + host + "...");

        System.out.println("I am a client!");

        new Thread(() ->
        {
            server = connectToServer(ip, port);

            // Update GUI since we connected.
            EventQueue.invokeLater(() -> {
                if (server != null) {
                    setTitle("Connected to " + server.getIp() + ":" + server.getPort());

                    changed = false;
                    Save.setEnabled(false);
                    SaveAs.setEnabled(false);
                    // old text is removed.
                    dec.clear();
                    er.setServer(server);
                } else {
                    area1.setText("Could not connect!");
                }
            });

            if (server != null) {
                String[] message = new String[1];
                // Start listening ourselves, so we can redirect new peers to the server.
                if (startListening(message, null, -1)) {
                    er.setListenEndPoint(getListenEndPoint());
                    EventQueue.invokeLater(() -> setTitle(message[0] + " - " + getTitle()));

                    while (true) {
                        try (Socket clientSocket = serverSocket.accept();
                             ObjectOutputStream oos = new ObjectOutputStream(clientSocket.getOutputStream())) {

                            // Connect to the server instead!
                            oos.writeObject(new RedirectPeer(true, server.getIp(), server.getPort()));

                            // Do not close client socket until he tells us it's ok - otherwise the connection could
                            // be closed before the client received the redirect message.
                            try (ObjectInputStream iis = new ObjectInputStream(clientSocket.getInputStream())) {
                                iis.readObject();
                            } catch (IOException ex) {
                                // Client socket closed, continue
                            }

                        } catch (IOException ex) {
                            // Server socket closed
                            break;
                        } catch (ClassNotFoundException e1) {
                            e1.printStackTrace();
                            break;
                        }
                    }
                }
            }
        }).start();
    }

    private String getListenEndPoint() {
        return serverSocket.getInetAddress().getHostAddress() + ":" + serverSocket.getLocalPort();
    }

    private Peer connectToServer(String ip, int port) {
        try {
            // Connect...
            Peer peer = new Peer(new Socket(ip, port));

            // If this guy we connected to is not the server, then we should
            // get redirected to the proper server.
            RedirectPeer redirectPeer = (RedirectPeer) peer.receive();

            while (redirectPeer.shouldRedirect()) {
                // Ok so we should redirect. Tell the peer that we have heard back so he can close us.
                peer.send(null);
                peer.close();
                // Try the new guy instead.
                peer = new Peer(new Socket(redirectPeer.getIpAddress(), redirectPeer.getPort()));

                redirectPeer = (RedirectPeer) peer.receive();
            }

            return peer;
        } catch (IOException e1) {
            return null;
        }
    }

    Action Disconnect = new AbstractAction("Disconnect") {
        public void actionPerformed(ActionEvent e) {
            disconnect();
        }
    };

    private void disconnect() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ex) {
                ex.printStackTrace();
            }

            serverSocket = null;
        }

        setDisconnected();
        er.disconnect();
    }

    public void setDisconnected() {
        if (serverSocket != null)
            return;

        setTitle("Disconnected");
    }

    Action Save = new AbstractAction("Save") {
        public void actionPerformed(ActionEvent e) {
            if (!currentFile.equals("Untitled"))
                saveFile(currentFile);
            else
                saveFileAs();
        }
    };

    Action SaveAs = new AbstractAction("Save as...") {
        public void actionPerformed(ActionEvent e) {
            saveFileAs();
        }
    };

    Action Quit = new AbstractAction("Quit") {
        public void actionPerformed(ActionEvent e) {
            saveOld();
            System.exit(0);
        }
    };

    ActionMap m = area1.getActionMap();

    Action Copy = m.get(DefaultEditorKit.copyAction);
    Action Paste = m.get(DefaultEditorKit.pasteAction);

    private void saveFileAs() {
        if (dialog.showSaveDialog(null) == JFileChooser.APPROVE_OPTION)
            saveFile(dialog.getSelectedFile().getAbsolutePath());
    }

    private void saveOld() {
        if (changed) {
            if (JOptionPane.showConfirmDialog(this, "Would you like to save " + currentFile + " ?", "Save", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION)
                saveFile(currentFile);
        }
    }

    private void saveFile(String fileName) {
        try {
            FileWriter w = new FileWriter(fileName);
            area1.write(w);
            w.close();
            currentFile = fileName;
            changed = false;
            Save.setEnabled(false);
        } catch (IOException e) {
        }
    }

    public void reconnect(String endPoint) {
        // If we are supposed to reconnect to ourself, then make us a server
        boolean isUs = serverSocket != null && endPoint.equals(getListenEndPoint());
        if (isUs) {
            String text = area1.getText();

            // Get old address we were listening on
            InetAddress address = serverSocket.getInetAddress();
            int port = serverSocket.getLocalPort();
            disconnect();

            EventQueue.invokeLater(() -> {
                // Clear out current events
                dec.clear();
                // Set the text to the original. This adds the first original event.
                area1.setText(text);
                // Start the server. Make sure we listen on the old address and port!
                listen(address, port, false);
            });
        } else {
            // Make sure we clean up after old session..
            disconnect();
            // Connect to whoever is server now.
            String[] split = endPoint.split(":");
            connect(split[0], Integer.parseInt(split[1]));
        }
    }

    public static void main(String[] arg) {
        new DistributedTextEditor();
    }
}
