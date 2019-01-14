package io.comrad.p2p;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.support.v4.app.ActivityCompat;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import io.comrad.R;
import io.comrad.music.MusicActivity;
import io.comrad.music.Song;
import io.comrad.p2p.messages.P2PMessage;
import io.comrad.p2p.messages.P2PMessageHandler;

import java.util.*;

import static android.bluetooth.BluetoothAdapter.*;
import static android.content.ContentValues.TAG;
import static io.comrad.p2p.messages.MessageType.song;
import static io.comrad.p2p.messages.MessageType.update_network_structure;

public class P2PActivity extends Activity {
    public final static String SERVICE_NAME = "COMRAD";
    public final static UUID SERVICE_UUID = UUID.fromString("7337958a-460f-4b0c-942e-5fa111fb2bee");

    private final static int REQUEST_ENABLE_BT = 1;
    private final static int REQUEST_DISCOVER = 2;
    private final static int PERMISSION_REQUEST = 3;
    static final int REQUEST_MUSIC_FILE = 4;

    private P2PServerThread serverThread;

    private P2PReceiver receiver;

    private final P2PMessageHandler handler = new P2PMessageHandler(this);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_p2p);

        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST);
        enableBluetooth();
    }

    private void enableBluetoothServices() {
        this.handler.onBluetoothEnable();

        final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        final List<String> peerList = new ArrayList<>();
        RecyclerView peerView = findViewById(R.id.peersList);

        LinearLayoutManager mng = new LinearLayoutManager(this);
        peerView.setLayoutManager(mng);

        final PeerAdapter peerAdapter = new PeerAdapter(peerList);
        peerView.setAdapter(peerAdapter);

        Button discoverButton = findViewById(R.id.discover);
        discoverButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(getApplicationContext(), "Discovering devices...", Toast.LENGTH_LONG).show();

                connectToBondedDevices(bluetoothAdapter, handler);

                if (bluetoothAdapter.isDiscovering()) {
                    bluetoothAdapter.cancelDiscovery();
                }
                peerList.clear();
                peerAdapter.notifyDataSetChanged();
                bluetoothAdapter.startDiscovery();
            }
        });


        receiver = new P2PReceiver(peerAdapter, handler);
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_UUID);
        registerReceiver(receiver, filter);

        this.serverThread = new P2PServerThread(BluetoothAdapter.getDefaultAdapter(), this.handler);
        this.serverThread.start();

    }

    private void enableBluetooth() {
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

        Button stopDiscovery = findViewById(R.id.stopDiscovery);
        stopDiscovery.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(getApplicationContext(), "Stopped discovering.", Toast.LENGTH_LONG).show();
                bluetoothAdapter.cancelDiscovery();
            }
        });

        Button sendMessage = findViewById(R.id.sendMessage);
        sendMessage.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                handler.sendMessageToPeers("Hello world!");
            }
        });

        Button chooseMusic = findViewById(R.id.chooseMusic);
        chooseMusic.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent intent = new Intent(v.getContext(), MusicActivity.class);
                startActivityForResult(intent, REQUEST_MUSIC_FILE);

            }
        });

        connectToBondedDevices(bluetoothAdapter, handler);

        if(bluetoothAdapter.isEnabled()) {
            enableBluetoothServices();
        }
    }


    private void connectToBondedDevices(BluetoothAdapter bluetoothAdapter, P2PMessageHandler handler) {
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();

        System.out.println("Paired devices:");
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                System.out.println(device.getAddress() + " : " + Arrays.toString(device.getUuids()));
                if(this.handler.hasPeer(device.getAddress()) || P2PConnectThread.isConnecting(device.getAddress())) {
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(requestCode == REQUEST_DISCOVER) {
            if(resultCode == SCAN_MODE_NONE) {
                Toast.makeText(getApplicationContext(), "This device is not connectable.", Toast.LENGTH_LONG).show();
            } else if(resultCode == SCAN_MODE_CONNECTABLE) {
                Toast.makeText(getApplicationContext(), "This device is not discoverable, but can be connected.", Toast.LENGTH_LONG).show();
            } else if(resultCode == SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                Toast.makeText(getApplicationContext(), "This device is now discoverable!", Toast.LENGTH_LONG).show();
            }
        }

        if(requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                enableBluetoothServices();
            } else if (!BluetoothAdapter.getDefaultAdapter().isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        }

        if(requestCode == REQUEST_MUSIC_FILE) {
            if (resultCode == RESULT_OK) {
//                handler.sendMessageToPeers("Hello world!");
                Song result = (Song) data.getParcelableExtra("song");
                P2PMessage p2pMessage = new P2PMessage("MEUK", update_network_structure, result);
                p2pMessage.addPayload(new byte[55536]);
                handler.sendMessageToPeers(p2pMessage);

//                Log.d(TAG, result.toString());
            } else {
                // ERROR?
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        if(requestCode == PERMISSION_REQUEST) {
            //TODO
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        unregisterReceiver(receiver);
        this.handler.closeAllConnections();

        serverThread.close();
    }
}
