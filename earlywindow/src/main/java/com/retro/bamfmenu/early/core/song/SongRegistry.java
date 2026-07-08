package com.retro.bamfmenu.early.core.song;

/* The song registry: the in-memory list the provider iterates (SONGS) instead of hardcoding song data +
   "recovery".equals(...) branches. As of the rows are no longer written here -- they are DISCOVERED
   from the bundled /songs/<id>/song.json descriptors by SongLoader, so adding a song is "drop a folder with a
   song.json + an OGG" with no Java edit.

   SONGS is sorted by authored order then id, with Recovery anchored to index 0 (the cutscene leads into it and
   the rest of the system reads SONGS[0] as Recovery). A song's bespoke CODE -- a reactive left panel and/or a
   SPECIAL session -- is wired in SongLoader.BUILTINS, keyed by id, since it can't live in JSON. */
public final class SongRegistry {

    private SongRegistry() {
    }

    public static final SongDef[] SONGS = SongLoader.discover();
}
