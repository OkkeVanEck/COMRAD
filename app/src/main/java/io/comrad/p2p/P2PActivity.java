package io.comrad.p2p;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.ContextCompat;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import io.comrad.R;
import io.comrad.music.MusicListFragment;
import io.comrad.music.PlayMusic;
import io.comrad.music.Song;
import io.comrad.p2p.messages.MessageType;
import io.comrad.p2p.messages.P2PMessage;
import io.comrad.p2p.messages.P2PMessageHandler;
import io.comrad.p2p.network.Graph;
import io.comrad.p2p.network.Node;
import nl.erlkdev.adhocmonitor.AdhocMonitorBinder;
import nl.erlkdev.adhocmonitor.AdhocMonitorService;

import java.io.*;
import java.util.*;

import static io.comrad.music.SongPacket.SONG_PACKET_SIZE;

public class P2PActivity extends FragmentActivity  {
    public final static String SERVICE_NAME = "COMRAD";
    public final static UUID SERVICE_UUID = UUID.fromString("7337958a-460f-4b0c-942e-5fa111fb2bee");

    private final static int REQUEST_ENABLE_BT = 1;
    private final static int REQUEST_DISCOVER = 2;
    private final static int LOCATION_PERMISSION = 3;
    private static final int REQUEST_MUSIC_FILE = 4;
    private static final int MUSIC_PERMISSION = 5;

    private ServiceConnection monitorService;
    private P2PServerThread serverThread;

    private P2PReceiver receiver;

    private ArrayList<Song> ownSongs = new ArrayList<>();

    private byte[] songBuffer;

    private AdhocMonitorService monitor;

    private final P2PMessageHandler handler = new P2PMessageHandler(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_p2p);

        if(ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, MUSIC_PERMISSION);
        } else {
            getMusic();
        }

        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);

        addComponents();
    }

    private void enableBluetoothServices() {
        this.handler.onBluetoothEnable(ownSongs);

        final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        Button discoverButton = findViewById(R.id.discover);
        discoverButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(getApplicationContext(), "Discovering devices...", Toast.LENGTH_LONG).show();

                connectToBondedDevices(bluetoothAdapter, handler);

                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }
                bluetoothAdapter.startDiscovery();
            }
        });

        receiver = new P2PReceiver(handler);
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_UUID);
        registerReceiver(receiver, filter);

        this.serverThread = new P2PServerThread(BluetoothAdapter.getDefaultAdapter(), this.handler);
        this.serverThread.start();

        connectToBondedDevices(bluetoothAdapter, handler);

        if(!this.handler.getNetwork().getSelfMac().equalsIgnoreCase("02:00:00:00:00:00")) {
            reattachMonitor();
        }
    }

    public void reattachMonitor() {
        Intent adhocIntent = new Intent(this, AdhocMonitorService.class);

        if(this.monitorService != null) {
            this.unbindService(this.monitorService);
        } else {
            startService(adhocIntent);
        }

        this.monitorService = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName className, IBinder service) {
                Log.d("MonitorService", "Adhoc Monitor service is connected");
                AdhocMonitorBinder adhocMonitorBinder = (AdhocMonitorBinder) service;
                monitor = adhocMonitorBinder.getService();

                /* Starts the monitor. */
                monitor.startMonitor(handler.getNetwork().getSelfMac(), "145.109.45.90");

                handler.onMonitorEnable(monitor);
            }

            @Override
            public void onServiceDisconnected(ComponentName arg0) {
                Log.d("MonitorService", "Adhoc Monitor service is disconnected");
            }
        };

        /* Bind monitor service. */
        bindService(adhocIntent, monitorService, BIND_AUTO_CREATE);
    }

    /*
     * Retrieves all music on the device and adds them to the class variable arrayList.
     */
    public void getMusic() {
        ContentResolver contentResolver = getContentResolver();
        Uri songUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        Cursor songCursor = contentResolver.query(songUri, null, null, null, null);

        if (songCursor != null && songCursor.moveToFirst()) {
            int songTitle = songCursor.getColumnIndex(MediaStore.Audio.Media.TITLE);
            int songArtist = songCursor.getColumnIndex(MediaStore.Audio.Media.ARTIST);
            int songLocation = songCursor.getColumnIndex(MediaStore.Audio.Media.DATA);
            int songSize = songCursor.getColumnIndex(MediaStore.Audio.Media.SIZE);
            String currentTitle;
            String currentArtist;
            String currentLocation;
            int currentSize;

            // Loop over the found songs and add them to the arraylist
            do {
                currentTitle = songCursor.getString(songTitle);
                currentArtist = songCursor.getString(songArtist);
                currentLocation = songCursor.getString(songLocation);
                currentSize = songCursor.getInt(songSize);
                ownSongs.add(new Song(currentTitle, currentArtist, currentLocation, currentSize));
            } while (songCursor.moveToNext());

            songCursor.close();
        }
    }

    private void addComponents() {
        final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            Toast.makeText(getApplicationContext(), "This device does not support bluetooth.", Toast.LENGTH_LONG).show();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        Button serverButton = findViewById(R.id.server);
        serverButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 3600);
                startActivityForResult(discoverableIntent, REQUEST_DISCOVER);
            }
        });

        Button showGraph = findViewById(R.id.showGraph);
        showGraph.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                synchronized (handler.getNetwork().getGraph()) {
                    System.out.println(handler.getNetwork().getGraph());
                }
            }
        });

        if (bluetoothAdapter.isEnabled()) {
            enableBluetoothServices();
        }
    }

    private void connectToBondedDevices(BluetoothAdapter bluetoothAdapter, P2PMessageHandler handler) {
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();

        System.out.println("Paired devices:");
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                System.out.println(device.getAddress() + " : " + Arrays.toString(device.getUuids()));
                if (this.handler.getNetwork().hasPeer(device.getAddress()) || P2PConnectThread.isConnecting(device.getAddress())) {
                    continue;
                }

                if (device.getUuids() != null) {
                    for (ParcelUuid uuid : device.getUuids()) {
                        if (uuid.getUuid().equals(P2PActivity.SERVICE_UUID)) {
                            new P2PConnectThread(device, handler).start();
                        }
                    }
                }
            }
        }
    }

    public void refreshPlaylist(Set<Node> nodes) {
        ArrayList<Song> arrayList = new ArrayList<>();
        for (Node node : nodes) {
            if (node.getPlaylist() != null) {
                arrayList.addAll(node.getPlaylist());
            }
        }

        MusicListFragment fragment = (MusicListFragment) getSupportFragmentManager().findFragmentById(R.id.MusicActivity);

        if (fragment != null) {
            fragment.setPlayList(arrayList);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_DISCOVER) {
            if (resultCode == RESULT_CANCELED) {
                Toast.makeText(getApplicationContext(), "This device is not discoverable.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(getApplicationContext(), "This device is now discoverable for " + resultCode + " seconds.", Toast.LENGTH_SHORT).show();
            }
        }

        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                enableBluetoothServices();
            } else if (!BluetoothAdapter.getDefaultAdapter().isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        }
    }

    public void requestSong(Song song) {
        Graph graph = this.handler.getNetwork().getGraph();
        synchronized (graph) {
            Node node = graph.getNearestSong(song);
            if(node == null) {
                throw new IllegalStateException("Song " + song + " was requested, but was not present in any of the nodes in the network.");
            }

            if(node.equals(this.handler.getNetwork().getGraph().getSelfNode())) {
                this.saveMusicBytePacket(0, this.getByteArrayFromSong(song));
                this.sendByteArrayToPlayMusic();
            } else {
                this.handler.getNetwork().forwardMessage(new P2PMessage(this.handler.getNetwork().getSelfMac(), node.getMac(), MessageType.request_song, song));
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if(requestCode == MUSIC_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getMusic();
            } else {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, MUSIC_PERMISSION);
            }
        }
    }

    public static byte[] convertStreamToByteArray(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buff = new byte[10240];
        int i;
        while ((i = is.read(buff, 0, buff.length)) > 0) {
            baos.write(buff, 0, i);
        }

        return baos.toByteArray();
    }

    public byte[] getByteArrayFromSong(Song song) {
        File songFile = new File(song.getSongLocation());
        InputStream inputStream;

        try {
            inputStream = new FileInputStream(songFile);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            this.handler.sendToastToUI("Could find file.");
            return null;
        }

        try {
            return convertStreamToByteArray(inputStream);
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    public void setSongSize(int size) {
        this.songBuffer = new byte[size + 10];
    }

    public void sendByteArrayToPlayMusic() {
        System.out.println("!~! Sending music to the media player..");
        PlayMusic fragment = (PlayMusic) getSupportFragmentManager().findFragmentById(R.id.PlayMusic);
        fragment.addSongBytes(this.songBuffer);
    }

    public void saveMusicBytePacket(int id, byte[] songBytes) {
        System.out.println("\n!~! Check for print");
        System.out.println("\n!~! Set buffer Size to: " + this.songBuffer.length);
        System.arraycopy(songBytes, 0, this.songBuffer, id * SONG_PACKET_SIZE, songBytes.length);

//        for (int i = id * SONG_PACKET_SIZE; i < songBytes.length; ++i) {
//            this.songBuffer[i] = songBytes[i];
//        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        unbindService(this.monitorService);

        unregisterReceiver(receiver);
        this.handler.getNetwork().closeAllConnections();

        serverThread.close();
    }

    public static String getBluetoothMac(final Context context) {
        String result = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            result = android.provider.Settings.Secure.getString(context.getContentResolver(), "bluetooth_address");
        }

        if(result == null) {
            BluetoothAdapter bta = BluetoothAdapter.getDefaultAdapter();
            result = bta != null ? bta.getAddress() : "02:00:00:00:00:00";
        }

        return result;
    }
}
