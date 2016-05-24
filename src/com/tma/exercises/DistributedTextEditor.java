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
            saveOld();
            area1.setText("");
            String[] message = new String[1];
            if (!startListening(message)) {
                area1.setText(message[0]);
                return;
            }

            setTitle(message[0]);

            dec.clear();
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
    };

    private boolean startListening(String[] listeningMessage) {
        int port;
        try {
            port = Integer.parseInt(portNumber.getText());
        } catch (NumberFormatException ex) {
            listeningMessage[0] = "Can't parse port number";
            return false;
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(InetAddress.getLocalHost().getHostName());
        } catch (UnknownHostException e) {
            listeningMessage[0] = "Could not start listening";
            return false;
        }

        Arrays.sort(addresses, (ad1, ad2) -> ad1.getHostAddress().compareTo(ad2.getHostAddress()));

        for (InetAddress address : addresses) {
            if (!(address instanceof Inet4Address))
                continue;

            try {
                serverSocket = new ServerSocket(port, 20, address);
                listeningMessage[0] = "Listening on " + address.getHostAddress() + ":" + port;
                return true;
            } catch (IOException ex) {
            }
        }

        listeningMessage[0] = "Could not start listening";
        return false;
    }

    Action Connect = new AbstractAction("Connect") {
        public void actionPerformed(ActionEvent e) {
            saveOld();
            area1.setText("");
            int port;
            try {
                port = Integer.parseInt(portNumber.getText());
            } catch (NumberFormatException ex) {
                area1.setText("Can't parse " + portNumber.getText());
                return;
            }


            String host = ipaddress.getText() + ":" + port;
            setTitle("Connecting to " + host + "...");

            System.out.println("I am a client!");

            new Thread(() ->
            {
                server = connectToServer(port);

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
                    if (startListening(message)) {
                        EventQueue.invokeLater(() -> setTitle(message[0] + " - " + getTitle()));

                        while (true) {
                            try (Socket clientSocket = serverSocket.accept();
                                 ObjectOutputStream oos = new ObjectOutputStream(clientSocket.getOutputStream())) {

                                oos.writeObject(new RedirectPeer(true, server.getIp(), server.getPort()));

                                // Do not close client socket until he tells us it's ok
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
    };

    private Peer connectToServer(int port) {
        try {
            Peer peer = new Peer(new Socket(ipaddress.getText(), port));

            RedirectPeer redirectPeer = (RedirectPeer) peer.receive();

            while (redirectPeer.shouldRedirect()) {
                // Tell server we're ok
                peer.send(null);
                peer.close();
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
    };

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

    public static void main(String[] arg) {
        new DistributedTextEditor();
    }


}
