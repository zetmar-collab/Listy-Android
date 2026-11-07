package pl.zetmar.studioplaylist;

import static com.google.common.truth.Truth.assertThat;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class StorageInstrumentedTest {
    private Storage storage;private Storage.Entry folder;private String tree;private final AtomicBoolean stop=new AtomicBoolean();
    @Before public void setup() throws Exception {
        // Temporary test-process identity, dropped in cleanup. Production requests user SAF grants.
        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS");
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();storage=new Storage(context);
        Uri root=DocumentsContract.buildTreeDocumentUri("pl.zetmar.studioplaylist.test.documents","root");
        Storage.Entry providerRoot=storage.describe(DocumentsContract.buildDocumentUriUsingTree(root,"root"));
        folder=storage.create(providerRoot,"run-"+UUID.randomUUID(),true);
        tree=DocumentsContract.buildTreeDocumentUri("pl.zetmar.studioplaylist.test.documents",DocumentsContract.getDocumentId(Uri.parse(folder.uri))).toString();
    }
    @After public void cleanup() throws Exception {try{if(folder!=null)clean(folder);}finally{InstrumentationRegistry.getInstrumentation().getUiAutomation().dropShellPermissionIdentity();}}
    private void clean(Storage.Entry e) throws Exception {if(e.directory)for(Storage.Entry child:storage.children(e,stop))clean(child);DocumentsContract.deleteDocument(InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver(),Uri.parse(e.uri));}
    @Test public void recursiveScanSeesAlbumAudioAndPlaylists() throws Exception {
        Storage.Entry album=storage.create(folder,"Zażółć Album",true);storage.create(album,"02 utwór.MP3",false);storage.create(album,"Album.m3u8",false);storage.create(album,"not-music.txt",false);
        Storage.Library flat=storage.scan(tree,false,false,stop,s->{});assertThat(flat.tracks).isEmpty();
        Storage.Library recursive=storage.scan(tree,true,false,stop,s->{});assertThat(recursive.tracks).hasSize(1);assertThat(recursive.playlists).hasSize(1);assertThat(recursive.tracks.get(0).path).isEqualTo("Zażółć Album/02 utwór.MP3");
    }
    @Test public void updateCreatesExactBackupAndVersioningDoesNotOverwrite() throws Exception {
        byte[] first="#EXTM3U\nold.mp3\n".getBytes(StandardCharsets.UTF_8),second="#EXTM3U\nnew.mp3\n".getBytes(StandardCharsets.UTF_8);
        storage.save(folder,"Album.m3u8",first,0,stop);storage.save(folder,"Album.m3u8",second,0,stop);
        assertThat(storage.read(storage.find(folder,"Album.m3u8",stop).uri)).isEqualTo(first);
        storage.save(folder,"Album.m3u8",second,1,stop);List<Storage.Entry> files=storage.children(folder,stop);assertThat(files).hasSize(2);
        Storage.Entry backup=files.stream().filter(e->e.name.endsWith(".bak")).findFirst().orElseThrow();assertThat(storage.read(backup.uri)).isEqualTo(first);
        storage.save(folder,"Album.m3u8",first,2,stop);assertThat(storage.find(folder,"Album (2).m3u8",stop)).isNotNull();assertThat(storage.read(storage.find(folder,"Album.m3u8",stop).uri)).isEqualTo(second);
    }
    @Test public void renameAndDeleteOnlyAffectPlaylists() throws Exception {
        Storage.Entry audio=storage.create(folder,"01.mp3",false);storage.write(audio.uri,new byte[]{1,2,3});storage.save(folder,"Album.pls","[playlist]".getBytes(StandardCharsets.UTF_8),0,stop);
        Storage.Entry renamed=storage.rename(storage.find(folder,"Album.pls",stop),"Nowa nazwa",stop);assertThat(renamed.name).isEqualTo("Nowa nazwa.pls");storage.deletePlaylist(renamed);assertThat(storage.read(audio.uri)).isEqualTo(new byte[]{1,2,3});
        boolean refused=false;try{storage.deletePlaylist(audio);}catch(Exception ex){refused=true;}assertThat(refused).isTrue();
    }
    @Test public void relativeReferencesResolveAgainstActualPlaylistFolder() throws Exception {
        Storage.Entry album=storage.create(folder,"Album",true);Storage.Entry song=storage.create(album,"01.mp3",false);storage.save(album,"Album.m3u8","#EXTM3U\n01.mp3\n".getBytes(StandardCharsets.UTF_8),0,stop);
        Storage.Library library=storage.scan(tree,true,false,stop,s->{});Storage.Entry playlist=library.playlists.get(0);List<Track> rows=storage.resolve(PlaylistCodec.parse(PlaylistCodec.decode(storage.read(playlist.uri)),"M3U8"),playlist,library);assertThat(DocumentsContract.getDocumentId(Uri.parse(rows.get(0).uri))).isEqualTo(DocumentsContract.getDocumentId(Uri.parse(song.uri)));assertThat(rows.get(0).missing).isFalse();
    }
    @Test public void allFormatsParseOnAndroidRuntime() throws Exception {Track t=new Track("/music/Zażółć & #1.mp3","Zażółć & #1");for(String format:PlaylistCodec.FORMATS){String text=PlaylistCodec.serialize(List.of(t),format,"Test","",false);assertThat(PlaylistCodec.parse(text,format)).hasSize(1);}}
    @Test(expected=CancellationException.class) public void cancelledScanStopsBeforeAnyTraversal() throws Exception {storage.scan(tree,true,false,new AtomicBoolean(true),s->{});}
}
