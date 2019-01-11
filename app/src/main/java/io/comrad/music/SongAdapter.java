package io.comrad.music;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import io.comrad.R;
import io.comrad.music.Song;


public class SongAdapter extends ArrayAdapter<Song> {
    private Context mContext;
    private List<Song> SongsList;

    public SongAdapter(@NonNull Context context, ArrayList<Song> list) {
        super(context, 0, list);
        mContext = context;
        SongsList = list;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        View listItem = convertView;

        if(listItem == null) {
            listItem = LayoutInflater.from(mContext).inflate(R.layout.song_item, parent, false);
        }

        Song currentSong = SongsList.get(position);

        TextView name = listItem.findViewById(R.id.textView_name);
        name.setText(currentSong.toString());

        return listItem;
    }
}