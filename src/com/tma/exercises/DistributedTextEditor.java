package com.tma.exercises;

import java.awt.*;
import java.awt.event.*;
import java.io.*;
import javax.swing.*;
import javax.swing.text.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;

public class DistributedTextEditor extends JFrame {

    private JTextArea area1 = new JTextArea(20, 120);
    private JTextArea area2 = new JTextArea(20, 120);
    private JTextField ipaddress = new JTextField("192.168.87.101");
    private JTextField portNumber = new JTextField("40615");

    private EventReplayer er;

    private JFileChooser dialog =
            new JFileChooser(System.getProperty("user.dir"));

    private String currentFile = "Untitled";
    private boolean changed = false;
    private DocumentEventCapturer dec = new DocumentEventCapturer();
    private ServerSocket serverSocket;

    public DistributedTextEditor() {
        area1.setFont(new Font("Monospaced", Font.PLAIN, 12));

        area2.setFont(new Font("Monospaced", Font.PLAIN, 12));
        ((AbstractDocument) area1.getDocument()).setDocumentFilter(dec);
        area2.setEditable(false);

        Container content = getContentPane();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JScrollPane scroll1 =
                new JScrollPane(area1,
                        JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                        JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
        content.add(scroll1, BorderLayout.CENTER);

        JScrollPane scroll2 =
                new JScrollPane(area2,
                        JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
                        JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
        content.add(scroll2, BorderLayout.CENTER);

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
        area1.insert("Example of how to capture stuff from the event queue and replay it in another buffer.\n" +
                "Try to type and delete stuff in the top area.\n" +
                "Then figure out how it works.\n", 0);

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
            int port;
            try {
                port = Integer.parseInt(portNumber.getText());
            } catch (NumberFormatException ex) {
                area1.setText("Can't parse " + portNumber.getText());
                return;
            }

            try {
                serverSocket = new ServerSocket(port);
            } catch (IOException ex) {
                area1.setText("Could not start listening" + System.lineSeparator() + ex);
                return;
            }

            try {
                InetAddress localhost = InetAddress.getLocalHost();
                String localhostAddress = localhost.getHostAddress();
                setTitle("I'm listening on " + localhostAddress + ":" + port);
            } catch (UnknownHostException ex) {
                area1.setText("Cannot resolve the Internet address of the local host.");
                return;
            }

            dec.setIsServer(true);

            System.out.println("I am the server!");
            new Thread(() -> {
                while (true) {
                    Socket clientSocket;
                    try {
                        clientSocket = serverSocket.accept();
                    } catch (IOException ex) {
                        ex.printStackTrace();
                        break;
                    }

                    EventQueue.invokeLater(()->{
                        // If we have a client already, throw him off as we're accepting
                        // a new one.
                        er.disconnectPeer();

                        // Clear old text in case we had previous clients that filled
                        // them up.
                        area1.setText("");
                        area2.setText("");

                        er.setPeer(clientSocket);
                    });
                }
            }).start();

            changed = false;
            Save.setEnabled(false);
            SaveAs.setEnabled(false);
        }
    };

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

            dec.setIsServer(false);
            System.out.println("I am a client!");

            new Thread(() ->
            {
                final Socket server = connectToServer(port);

                EventQueue.invokeLater(() -> {
                    if (server != null) {
                        setTitle("Connected to " + host);

                        changed = false;
                        Save.setEnabled(false);
                        SaveAs.setEnabled(false);
                        // old text is removed.
                        area2.setText("");
                        er.setPeer(server);
                    } else {
                        area1.setText("Could not connect!");
                    }
                });
            }).start();
        }
    };

    private Socket connectToServer(int port) {
        try {
            return new Socket(ipaddress.getText(), port);
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
            er.disconnectPeer();
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
