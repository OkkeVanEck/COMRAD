package io.comrad.music;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import java.util.ArrayList;

import io.comrad.R;


public class MusicActivity extends Activity {

    static final int REQUEST_MUSIC_FILE = 4;

    private static final int MY_PERMISSION_REQUEST = 1;
    ArrayList<Song> playList;
    ListView listView;
    ArrayAdapter<Song> adapter;
    String owner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_music);

        Intent intent = getIntent();
        playList = (ArrayList<Song>) intent.getSerializableExtra("Nodes");

        System.out.println("<<<<<" + playList);

        showMusic();
//        if(ContextCompat.checkSelfPermission(MusicActivity.this,
//                Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
//            if(ActivityCompat.shouldShowRequestPermissionRationale(MusicActivity.this,
//                    Manifest.permission.READ_EXTERNAL_STORAGE)) {
//                ActivityCompat.requestPermissions(MusicActivity.this,
//                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, MY_PERMISSION_REQUEST);
//            } else {
//                ActivityCompat.requestPermissions(MusicActivity.this,
//                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, MY_PERMISSION_REQUEST);
//            }
//        } else {
//            showMusic();
//        }
    }


    /*
     * Calls functions to retrieve all music on the device and shows them in a listview.
     */
    public void showMusic() {
        listView = findViewById(R.id.musicView);
//        getMusic();
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, playList);
        listView.setAdapter((adapter));

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                Intent result = new Intent();
                Log.d("songClick", playList.get(i).toString());
                result.putExtra("song", (Parcelable)playList.get(i));
                setResult(Activity.RESULT_OK, result);
                finish();

                // TODO open music player to play desired song.
                // playMp3Bytes(BYTE STREAM HERE);
            }
        });

        // TODO Use this code to add songs coming from the network.
//        this.addSong(new Song("Heyheya", "Jemoeder", "../java.meme", 5));
    }


    /*
     * Adds a song to the adapter for viewing in the listview.s
     */
    public void addSong(Song song) {
        adapter.add(song);
    }

    /*
     * Adds multiple songs to the adapter for viewing in the lisview.
     */
    public void addSongs(ArrayList<Song> songs) {
        adapter.addAll(songs);
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
                playList.add(new Song(currentTitle, currentArtist, currentLocation, currentSize));
            } while (songCursor.moveToNext());
        }
    }

    /*
     * Asks for a permission and shows the result in a toast.
     */
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSION_REQUEST: {
                if(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if(ContextCompat.checkSelfPermission(MusicActivity.this,
                            Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this, "Permission granted!", Toast.LENGTH_SHORT).show();

                        showMusic();
                    }
                } else {
                    Toast.makeText(this, "No permission granted!", Toast.LENGTH_SHORT).show();
                    finish();
                }
                return;
            }
        }
    }
}
