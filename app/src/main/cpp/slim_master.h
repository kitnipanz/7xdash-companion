#ifndef SLIM_MASTER_H
#define SLIM_MASTER_H

/*
 * 7xDash: keep only what the car plays from a YouTube HLS master playlist (H.264 at
 * 360p-1080p, the audio groups those use, every language), so the receiver fetches far fewer
 * media playlists through the iPhone before playback. Takes ownership of master (frees it when
 * it returns a new string); returns master itself when nothing would be left.
 */
char *slim_master_playlist(char *master, int *variants_before, int *variants_after,
                           int *renditions_before, int *renditions_after);

#endif
