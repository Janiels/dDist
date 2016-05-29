package com.tma.exercises;

import javax.swing.*;
import java.awt.EventQueue;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.OptionalInt;

/**
 * Takes the event recorded by the DocumentEventCapturer and replays
 * them in a JTextArea. The delay of 1 sec is only to make the individual
 * steps in the reply visible to humans.
 *
 * @author Jesper Buus Nielsen
 */
public class EventReplayer {

    private final DistributedTextEditor editor;
    private DocumentEventCapturer dec;
    private JTextArea area;
    private final ArrayList<Peer> peers = new ArrayList<>();
    private Thread send;
    private final ArrayList<String> serverPeerEndPoints = new ArrayList<>();

    public EventReplayer(DocumentEventCapturer dec, JTextArea area, DistributedTextEditor editor) {
        this.dec = dec;
        this.area = area;
        this.editor = editor;
        send = new Thread(() -> sendToPeers());
        send.start();
    }

    private void acceptFromPeer(Peer peer, boolean isClient) {
        try {
            // If we aren't the server then wait for the initial welcome from the server.
            // This contains our index in the vector clocks array.
            if (isClient) {
                Welcome welcome = (Welcome) peer.receive();
                System.out.println("Received welcome! My index is " + welcome.getIndex());
                EventQueue.invokeLater(() -> {
                    dec.setOurIndex(welcome.getIndex());
                });
            }

            while (true) {
                Object message = peer.receive();
                if (message instanceof PeerChange) {
                    PeerChange peerChange = (PeerChange) message;
                    if (isClient) {
                        // Received updated information about a peer and we are a client. This means another peer has
                        // connected or disconnected, so update our view of other peers.
                        if (peerChange.isConnected()) {
                            serverPeerEndPoints.add(peerChange.getEndPoint());
                            System.out.println("Added peer end point '" + peerChange.getEndPoint() + "'");
                        } else {
                            boolean removed = serverPeerEndPoints.remove(peerChange.getEndPoint());
                            System.out.println("Removed peer '" + peerChange.getEndPoint() + "': " + removed);
                        }
                    } else {
                        // Otherwise we assume this is the IP the peer we received the message from is listening on.
                        assert peerChange.isConnected();
                        System.out.println("Client '" + peer.getIp() + ":" + peer.getPort() + "' is listening on '" + peerChange.getEndPoint() + "'");
                        peer.setListenEndPoint(peerChange.getEndPoint());

                        // Send this out to all other peers.
                        synchronized (peers) {
                            for (Peer otherPeer : peers) {
                                if (otherPeer != peer)
                                    otherPeer.send(peerChange);
                            }
                        }
                    }
                } else {
                    MyTextEvent event = (MyTextEvent) message;
                    EventQueue.invokeLater(() -> {
                        System.out.println("Receive: " + event);
                        // Make sure later events are timestamped correctly according
                        // to this received one..
                        dec.clocksReceived(event.getClocks());

                        // If we're the server then send this event out to all
                        // other peers
                        if (!isClient) {
                            try {
                                dec.eventHistory.put(event);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }

                        // Do not capture received events again
                        dec.setEnabled(false);
                        try {
                            performEvent(event);
                        } catch (Exception e) {
                            System.err.println(e);
                            // We catch all exceptions, as an uncaught exception would make the
                            // EDT unwind, which is not healthy.
                        } finally {
                            dec.setEnabled(true);
                        }

                        System.out.println("");
                    });
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();

            try {
                peer.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (isClient) {
                // Server disconnected. Sort the end points; this ensures everyone
                // connects to the same peer.
                serverPeerEndPoints.sort((s1, s2) -> s1.compareTo(s2));
                if (serverPeerEndPoints.size() > 0)
                    // Connect to the first peer in the list.
                    editor.reconnect(serverPeerEndPoints.get(0));
            } else {
                // A client disconnected. Remove him from peers and tell all other peers
                // that he no longer exists.
                synchronized (peers) {
                    peers.remove(peer);

                    for (Peer otherPeer : peers) {
                        try {
                            otherPeer.send(new PeerChange(false, peer.getListenEndPoint()));
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }
    }

    private void performEvent(MyTextEvent newEvent) {
        // Get the events that are concurrent or happened after.
        ArrayList<MyTextEvent> events = dec.popEvents();

        // Roll back everything
        for (MyTextEvent event : events) {
            event.setAdjustOffset(0);
        }

        StringBuilder text = new StringBuilder("");

        // Add our new event
        events.add(newEvent);

        // Create peer-local lists of events in the correct peer order.
        OptionalInt maxIndex = events.stream().mapToInt(MyTextEvent::getSourceIndex).max();
        ArrayList<ArrayList<MyTextEvent>> lists = new ArrayList<>();
        for (int i = 0; i <= maxIndex.getAsInt(); i++)
            lists.add(new ArrayList<>());

        for (MyTextEvent eventToRedo : events) {
            lists.get(eventToRedo.getSourceIndex()).add(eventToRedo);
        }

        // Now merge events into text field.
        // Keep track of index into each peer's events.
        int[] listIndices = new int[lists.size()];
        ArrayList<MyTextEvent> performed = new ArrayList<>();
        while (true) {
            // Find the earliest event of all current peer events.
            MyTextEvent first = findEarliestEvent(lists, listIndices);
            if (first == null)
                break;

            // Redo this event, making sure to adjust indices for concurrent events.
            redoEvent(text, first, performed);
            // Merged this event.
            listIndices[first.getSourceIndex()]++;
        }

        for (MyTextEvent event : performed)
            dec.insertAppliedEvent(event);

        // Save selection
        int selectStart = area.getSelectionStart();
        int selectEnd = area.getSelectionEnd();
        // Update to new merged text
        area.setText(text.toString());

        // Restore selection
        area.setCaretPosition(selectEnd + newEvent.getAdjustOffset(selectEnd));
        area.moveCaretPosition(selectStart + newEvent.getAdjustOffset(selectStart));
    }

    private MyTextEvent findEarliestEvent(ArrayList<ArrayList<MyTextEvent>> lists, int[] listIndices) {
        // Finds the earliest event in the current peer events.
        MyTextEvent earliest = null;
        for (int i = 0; i < listIndices.length; i++) {
            if (listIndices[i] >= lists.get(i).size())
                continue;

            MyTextEvent event = lists.get(i).get(listIndices[i]);
            if (earliest == null || event.happenedBefore(earliest))
                earliest = event;
        }

        return earliest;
    }

    private void redoEvent(StringBuilder text, MyTextEvent event, ArrayList<MyTextEvent> performed) {
        boolean skip = false;
        int adjustOffset = 0;
        for (int j = 0; j < performed.size(); j++) {
            MyTextEvent performedEvent = performed.get(j);
            // If the event we have already performed happened before this new event, then we know
            // the source of the event will already have taken it into account.
            if (performedEvent.happenedBefore(event))
                continue;

            // Otherwise we need to adjust the indices. First make
            // sure we ignore duplicate concurrent removes.
            if (performedEvent instanceof TextRemoveEvent
                    && event instanceof TextRemoveEvent
                    && performedEvent.getOffset() + performedEvent.getAdjustOffset() == event.getOffset() + event.getAdjustOffset()) {
                System.out.println("Ignoring duplicate concurrent remove: " + event);
                skip = true;
                break;
            }

            adjustOffset += performedEvent.getAdjustOffset(event.getOffset() + adjustOffset);
        }

        if (!skip) {
            event.setAdjustOffset(adjustOffset);
            System.out.println("Reapply: " + event.toString());
            event.perform(text);
            performed.add(event);
        }
    }

    private void sendToPeers() {
        while (true) {
            MyTextEvent event;
            try {
                event = dec.take();
            } catch (InterruptedException e) {
                return;
            }

            System.out.println("Sending: " + event);
            synchronized (peers) {
                for (Peer peer : peers) {
                    // Don't send back to source peer
                    if (peer.getIndex() == event.getSourceIndex())
                        continue;

                    try {
                        peer.send(event);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    public void addPeer(Peer peer) {
        EventQueue.invokeLater(() -> {
            synchronized (peers) {
                // Server always has index 0. If we have 0 peers, the new peer should have index 1,
                // so add 1.
                int index = peers.size() + 1;
                peer.setIndex(index);

                try {
                    // Tell the peer that we are the server, so no need to redirect to someone else.
                    peer.send(new RedirectPeer(false, null, 0));
                    // Give him his index too.
                    peer.send(new Welcome(index));

                    // Tell the new peer the IPs that the old peers are listening on so the new
                    // peer can reconnect if I (the server) crash or is closed.
                    for (Peer otherPeer : peers) {
                        String listenEndPoint = otherPeer.getListenEndPoint();
                        if (listenEndPoint != null)
                            peer.send(new PeerChange(true, listenEndPoint));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // Tell new peer how the current text field looks.
                for (MyTextEvent event : dec.getEvents())
                    try {
                        peer.send(event);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                peers.add(peer);

                // Finally begin accepting text changes from this peer.
                new Thread(() -> acceptFromPeer(peer, false)).start();
            }
        });
    }

    public void setServer(Peer server) {
        // Server has index 0
        server.setIndex(0);

        synchronized (peers) {
            peers.add(server);
        }

        new Thread(() -> acceptFromPeer(server, true)).start();
    }

    public void disconnect() {
        synchronized (peers) {
            for (Peer peer : peers) {
                try {
                    peer.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            serverPeerEndPoints.clear();
            peers.clear();
            dec.clear();
        }

        editor.setDisconnected();
    }

    public void setListenEndPoint(String listenEndPoint) {
        serverPeerEndPoints.add(listenEndPoint);
        // Tell server the address we are listening on
        synchronized (peers) {
            // Should only be called for clients
            assert peers.size() == 1;
            for (Peer peer : peers) {
                try {
                    peer.send(new PeerChange(true, listenEndPoint));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
