package io.comrad.p2p.messages;

import android.bluetooth.BluetoothDevice;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import io.comrad.music.Song;
import io.comrad.p2p.network.Graph;
import io.comrad.p2p.network.GraphUpdate;

import static io.comrad.p2p.messages.MessageType.song;

public class P2PMessage implements Serializable {
    private String destinationMAC;
    private MessageType type;
    private Serializable payload;
    private String sourceMac;
    private int SONG_PACKET_SIZE = 256000;

    public P2PMessage(String sourceMac, String destinationMAC, MessageType type, Serializable payload) {
        this.sourceMac = sourceMac;
        this.destinationMAC = destinationMAC;
        this.type = type;
        addPayload(payload);
    }

    public String getSourceMac()
    {
        return this.sourceMac;
    }

    public String getDestinationMAC() {
        return this.destinationMAC;
    }

    public MessageType getType() {
        return this.type;
    }

    public byte[] toByteArray() throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        byte[] result;
        try {
            ObjectOutputStream objStream = new ObjectOutputStream(byteStream);
            objStream.writeObject(this);
            objStream.flush();
            result = byteStream.toByteArray();
        } finally {
            byteStream.close();
        }
        return result;
    }

    public void addPayload(Serializable payload) {
        try {
            switch (this.type) {
                case playlist:
                    break;
                case song:
//                    String fileURI = (String) payload;
//                    this.payload = readAudioFile(fileURI);
                    this.payload = payload;
                    break;
                case request_song:
                case send_song:
                case send_message:
                case update_network_structure:
                case handshake_network:
                case broadcast_message:
                    this.payload = payload;
                    break;
                default:
                    throw new IllegalStateException("Payload case was not handled");
            }
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    public void handle(P2PMessageHandler handler, BluetoothDevice sender) {
        /* If starts with b:, it's a broadcast. */
        if(this.destinationMAC != null && this.destinationMAC.startsWith("b:")) {
            handler.sendToastToUI("We received a broadcast from " + sender.getAddress());

            System.out.println("Incoming broadcast: " + this.destinationMAC);
            String mac = this.sourceMac;
            System.out.println(mac);

            if(mac.equalsIgnoreCase(handler.getNetwork().getSelfMac()))
            {
                System.out.println("Source Mac was our own, skipping...");
                return;
            }

            int count = Integer.parseInt(this.destinationMAC.substring(2));
            System.out.println(count);

            Set<Integer> knownCounts = handler.getNetwork().counters.get(mac);
            if (knownCounts == null) {
                knownCounts = new HashSet<>();
                handler.getNetwork().counters.put(mac, knownCounts);
            }
            if (knownCounts.contains(count)) {
                System.out.println(count);
                return;
            }
            knownCounts.add(count);
            handler.getNetwork().broadcastExcluding(this, sender.getAddress());
        }

        System.out.println("Source Mac: " + this.sourceMac);
        System.out.println("Gotten from-Mac: " + sender.getAddress());
        System.out.println("Desitnation mac: " + this.getDestinationMAC());
        System.out.println("Type: " + this.type);
        System.out.println("Message: " + this.payload);

        if (this.type == song) {
            handler.sendSongToActivity((byte[]) this.payload);
        } else if (this.type == MessageType.handshake_network) {
            Graph graph = (Graph) this.payload;
            graph.replace("02:00:00:00:00:00", this.sourceMac);

            synchronized (handler.getNetwork().getGraph()) {
                if(handler.getNetwork().getSelfMac().equalsIgnoreCase("02:00:00:00:00:00"))
                {
                    handler.getNetwork().getGraph().setSelfNode(this.getDestinationMAC());
                    System.out.println("Received MAC from sender: " + handler.getNetwork().getSelfMac());
                    handler.reattachMonitor();
                }

                GraphUpdate update = handler.getNetwork().getGraph().difference(graph);
                update.addEdge(handler.getNetwork().getSelfMac(), sender.getAddress());

                System.out.println("Update: " + update);
                handler.getNetwork().getGraph().apply(update);
                System.out.println("Network: " + handler.getNetwork());
                System.out.println("------------------------");
                // Send update to all but source.
                P2PMessage message = new P2PMessage(handler.getNetwork().getSelfMac(), handler.getNetwork().getBroadcastAddress(), MessageType.update_network_structure, update);
                handler.getNetwork().broadcastExcluding(message, sender.getAddress());
            }
        } else if(this.type == MessageType.update_network_structure) {
            GraphUpdate update = (GraphUpdate) this.payload;
            synchronized (handler.getNetwork().getGraph()) {
                handler.getNetwork().getGraph().apply(update);
                System.out.println("Updated network to: " + handler.getNetwork().getGraph());
            }
        } else if (this.type == MessageType.send_message) {
            if (this.getDestinationMAC().equalsIgnoreCase(handler.getNetwork().getSelfMac())) {
                handler.sendToastToUI("We received a message from " + this.sourceMac);
                handler.sendSongToActivity((byte[]) this.payload);


            } else {
                handler.getNetwork().forwardMessage(this);
            }
        } else if (this.type == MessageType.request_song) {
            if (this.getDestinationMAC().equalsIgnoreCase(handler.getNetwork().getSelfMac())) {
                handler.sendToastToUI("We received a request from " + this.sourceMac);

                byte[] payload = handler.getNetwork().getByteArrayFromSong((Song) this.payload);
                P2PMessage message;
                byte[] tmpPayload;

                for (int i = 0; i < payload.length; i += SONG_PACKET_SIZE) {
                    try {
                        tmpPayload = Arrays.copyOfRange(payload, i, i + SONG_PACKET_SIZE);
                    } catch (IndexOutOfBoundsException e) {
                        tmpPayload = Arrays.copyOfRange(payload, i, payload.length);
                    }

                    message = new P2PMessage(handler.getNetwork().getSelfMac(), this.sourceMac,
                                                        MessageType.send_song, tmpPayload);
                    handler.getNetwork().forwardMessage(message);
                }

                message = new P2PMessage(handler.getNetwork().getSelfMac(), this.sourceMac,
                        MessageType.song_finished, null);
                handler.getNetwork().forwardMessage(message);
            } else {
                handler.getNetwork().forwardMessage(this);
            }
        } else if (this.type == MessageType.send_song) {
            if (this.getDestinationMAC().equalsIgnoreCase(handler.getNetwork().getSelfMac())) {
                handler.sendToastToUI("We received a song from " + this.sourceMac);
                handler.sendSongToActivity((byte[]) this.payload);
            } else {
                handler.getNetwork().forwardMessage(this);
            }
        } else if (this.type == MessageType.song_finished) {
            handler.sendSongFinshed();
        }
    }

    private static Serializable readAudioFile(String fileURI) throws IOException {
        File file = new File(fileURI);
        FileInputStream fin = new FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        int bytesRead = fin.read(data);

        if(bytesRead != -1) {
            throw new IllegalStateException("Could not convert entire audio file to byte stream.");
        }

        fin.close();
        return data;
    }

    private static Object readObject(byte[] payload) throws IOException {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(payload);
        Object result = null;
        try {
            ObjectInputStream objStream = new ObjectInputStream(byteStream);
            result = objStream.readObject();
        } catch(ClassNotFoundException e) {
            e.printStackTrace();
        } finally {
            byteStream.close();
        }

        return result;
    }

    public static P2PMessage readMessage(ObjectInputStream byteStream) throws IOException {
        P2PMessage msg = null;

        try {
            msg = (P2PMessage) byteStream.readObject();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        //TODO: IOException

        if(msg == null) {
            throw new IllegalArgumentException("Byte stream could not be converted to a message, but instead was: null");
        }

        return msg;
    }
}
