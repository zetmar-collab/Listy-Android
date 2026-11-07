package pl.zetmar.studioplaylist;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native phone/tablet UI; file operations are confined to a single background worker. */
@SuppressWarnings("deprecation")
public class MainActivity extends Activity {
    private static final int TREE=10,ADD=11,OPEN=12,EXPORT=13;
    private static final int INK=0xff172f36,MUTED=0xff52716e,GREEN=0xff246b60,BG=0xfff2f6f4;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private AtomicBoolean cancel=new AtomicBoolean();
    private Storage storage;
    private Storage.Library library;
    private SharedPreferences prefs;
    private boolean english,busy,recursive=true,relative=true,tags,dirty;
    private String tree="",playlistName="Moja playlista",subfolder="Playlisty",query="",report="",progress="";
    private int tab,format,mode,policy,sort;
    private Set<String> extensions=new LinkedHashSet<>(PlaylistCodec.AUDIO),selected=new HashSet<>();
    private List<Track> editor=new ArrayList<>();
    private final Deque<List<Track>> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private Storage.Entry opened;
    private String importedRaw="";
    private int focused=-1;
    private LinearLayout screen,body,contentRows;
    private TextView progressLabel;
    private byte[] exportBytes;
    private String exportName;

    private String l(String pl,String en) {return english?en:pl;}
    private int dp(float value) {return (int)(value*getResources().getDisplayMetrics().density+.5f);}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);storage=new Storage(this);prefs=getSharedPreferences("listy",MODE_PRIVATE);
        english=prefs.getBoolean("english",!Locale.getDefault().getLanguage().equals("pl"));tree=prefs.getString("tree","");
        recursive=prefs.getBoolean("recursive",true);relative=prefs.getBoolean("relative",true);tags=prefs.getBoolean("tags",false);
        format=prefs.getInt("format",0);mode=prefs.getInt("mode",0);policy=prefs.getInt("policy",0);sort=prefs.getInt("sort",0);
        playlistName=prefs.getString("name",l("Moja playlista","My playlist"));subfolder=prefs.getString("subfolder","Playlisty");
        extensions=new LinkedHashSet<>(prefs.getStringSet("extensions",PlaylistCodec.AUDIO));
        selected=new HashSet<>(prefs.getStringSet("selected",Collections.emptySet()));
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::handleBack);
        restoreDraft();render();if(!tree.isEmpty())scan();
    }
    @Override public void onConfigurationChanged(Configuration c) {super.onConfigurationChanged(c);render();}
    @Override protected void onPause() {super.onPause();savePreferences();persistDraft();}
    @Override protected void onDestroy() {cancel.set(true);io.shutdown();super.onDestroy();}
    private void savePreferences() {
        if(prefs==null)return;
        prefs.edit().putBoolean("english",english).putString("tree",tree).putBoolean("recursive",recursive).putBoolean("relative",relative)
            .putBoolean("tags",tags).putInt("format",format).putInt("mode",mode).putInt("policy",policy).putInt("sort",sort)
            .putString("name",playlistName).putString("subfolder",subfolder).putStringSet("extensions",new HashSet<>(extensions)).putStringSet("selected",new HashSet<>(selected)).apply();
    }
    private static List<Track> copy(List<Track> source) {List<Track> r=new ArrayList<>();for(Track t:source)r.add(t.copy());return r;}
    private void persistDraft() {
        try {
            JSONObject data=new JSONObject();data.put("name",playlistName);data.put("dirty",dirty);data.put("format",format);data.put("imported",!importedRaw.isEmpty());
            if(opened!=null) {JSONObject e=new JSONObject();e.put("uri",opened.uri);e.put("name",opened.name);e.put("path",opened.path);e.put("parentUri",opened.parentUri);e.put("parentPath",opened.parentPath);data.put("opened",e);}
            JSONArray rows=new JSONArray();for(Track t:editor) {JSONObject o=new JSONObject();o.put("uri",t.uri);o.put("path",t.path);o.put("location",t.location);o.put("title",t.title);o.put("artist",t.artist);o.put("album",t.album);o.put("duration",Double.isFinite(t.duration)?t.duration:-1);o.put("missing",t.missing);rows.put(o);}data.put("tracks",rows);
            byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);
            io.execute(()-> {android.util.AtomicFile f=new android.util.AtomicFile(new File(getFilesDir(),"draft.json"));FileOutputStream out=null;try{out=f.startWrite();out.write(bytes);f.finishWrite(out);}catch(Exception ex){if(out!=null)f.failWrite(out);}});
        } catch(Exception ignored) { }
    }
    private void restoreDraft() {
        File file=new File(getFilesDir(),"draft.json");if(!file.exists() || file.length()>32*1024*1024)return;
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream buffer=new ByteArrayOutputStream()) {byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)buffer.write(b,0,n);
            JSONObject o=new JSONObject(buffer.toString("UTF-8"));playlistName=o.optString("name",playlistName);dirty=o.optBoolean("dirty");format=o.optInt("format",format);importedRaw=o.optBoolean("imported")?"restored":"";
            JSONObject e=o.optJSONObject("opened");if(e!=null)opened=new Storage.Entry(e.getString("uri"),e.getString("name"),e.getString("path"),e.getString("parentUri"),e.getString("parentPath"),false,0);
            JSONArray a=o.getJSONArray("tracks");for(int i=0;i<a.length();i++){JSONObject t=a.getJSONObject(i);Track row=new Track(t.getString("location"),t.getString("title"));row.uri=t.optString("uri");row.path=t.optString("path");row.artist=t.optString("artist");row.album=t.optString("album");row.duration=t.optDouble("duration",-1);row.missing=t.optBoolean("missing");editor.add(row);}
            if(dirty || !editor.isEmpty())tab=2;
        }catch(Exception ex){report=l("Nie udało się przywrócić szkicu: ","Could not restore draft: ")+ex.getMessage();}
    }
    private GradientDrawable shape(int color,int radius) {GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout column() {LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row() {LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,int size,int color) {TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(4),0,dp(4));return t;}
    private TextView heading(String value) {TextView t=text(value,20,INK);t.setTypeface(null,Typeface.BOLD);return t;}
    private Button button(String label,Runnable action) {Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(GREEN);b.setMinHeight(dp(48));b.setEnabled(!busy);if(label.equals("↑"))b.setContentDescription(l("Przesuń utwór w górę","Move track up"));if(label.equals("↓"))b.setContentDescription(l("Przesuń utwór w dół","Move track down"));b.setOnClickListener(v->{if(!busy)action.run();});return b;}
    private void buttons(LinearLayout parent,Button... buttons) {LinearLayout r=row();for(Button b:buttons)r.addView(b,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1));parent.addView(r);}
    private LinearLayout card() {LinearLayout c=column();c.setPadding(dp(16),dp(12),dp(16),dp(12));c.setBackground(shape(Color.WHITE,18));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(8),0,dp(8));body.addView(c,p);return c;}
    private void watch(EditText e,java.util.function.Consumer<String> changed) {e.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int start,int before,int count){changed.accept(s.toString());}public void afterTextChanged(Editable x){}});}
    private EditText input(String hint,String value,java.util.function.Consumer<String> changed) {EditText e=new EditText(this);e.setSingleLine(true);e.setTextSize(15);e.setText(value);e.setHint(hint);e.setContentDescription(hint);e.setEnabled(!busy);watch(e,changed);return e;}
    private CheckBox check(String label,boolean checked,java.util.function.Consumer<Boolean> action) {CheckBox c=new CheckBox(this);c.setText(label);c.setTextColor(INK);c.setChecked(checked);c.setEnabled(!busy);c.setMinHeight(dp(48));c.setOnCheckedChangeListener((v,b)->action.accept(b));return c;}
    private void choice(LinearLayout parent,String label,String[] items,int current,java.util.function.IntConsumer changed) {
        parent.addView(text(label,13,MUTED));Spinner s=new Spinner(this);ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,items);s.setAdapter(a);s.setSelection(Math.min(current,items.length-1));s.setContentDescription(label);s.setEnabled(!busy);s.setMinimumHeight(dp(48));parent.addView(s);
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){boolean first=true;public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int i,long id){if(first){first=false;return;}changed.accept(i);savePreferences();}});
    }
    private void render() {
        screen=column();screen.setBackgroundColor(INK);
        // Apply system bars and keyboard insets on Android's mandatory edge-to-edge layout.
        screen.setOnApplyWindowInsetsListener((v,insets)-> { if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout header=row();header.setPadding(dp(16),dp(10),dp(12),dp(10));header.setBackgroundColor(INK);
        LinearLayout brand=column();TextView title=text("Studio playlist",23,Color.WHITE);title.setTypeface(null,Typeface.BOLD);brand.addView(title);brand.addView(text(l("Twoja muzyka. Twoja kolejność.","Your music. Your order."),12,0xffa8e5c6));header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        Button language=button(english?"PL":"EN",()->{english=!english;query="";savePreferences();render();});language.setTextColor(Color.WHITE);language.setBackground(shape(GREEN,10));header.addView(language,new LinearLayout.LayoutParams(dp(58),dp(52)));screen.addView(header);
        LinearLayout tabs=row();tabs.setBackgroundColor(BG);String[] names={l("Tworzenie","Create"),l("Zapisane","Saved"),l("Edytor","Editor")};for(int i=0;i<3;i++){final int n=i;Button b=button(names[i],()->{tab=n;query="";render();});if(i==tab)b.setBackground(shape(0xffd9eee3,8));tabs.addView(b,new LinearLayout.LayoutParams(0,dp(52),1));}screen.addView(tabs);
        LinearLayout status=column();status.setBackgroundColor(BG);status.setPadding(dp(16),0,dp(16),0);progressLabel=text(busy?progress:l("Lokalnie • bez konta i reklam","Local • no account or ads"),12,MUTED);status.addView(progressLabel);
        if(busy){ProgressBar p=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);p.setIndeterminate(true);status.addView(p);Button stop=new Button(this);stop.setText(l("Anuluj operację","Cancel operation"));stop.setOnClickListener(v->{cancel.set(true);stop.setEnabled(false);});status.addView(stop);}screen.addView(status);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(BG);scroll.setFillViewport(true);body=column();body.setPadding(dp(16),dp(4),dp(16),dp(24));body.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);scroll.addView(body);screen.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(tab==0)renderCreate();else if(tab==1)renderSaved();else renderEditor();
        LinearLayout footer=row();footer.setBackgroundColor(BG);footer.setPadding(dp(12),0,dp(12),0);footer.addView(button(l("Raport","Report"),()->showText(l("Raport","Report"),report.trim().isEmpty()?l("Brak operacji.","No operations yet."):report)),new LinearLayout.LayoutParams(0,-2,1));footer.addView(button(l("O aplikacji","About"),this::about),new LinearLayout.LayoutParams(0,-2,1));screen.addView(footer);
        setContentView(screen);screen.requestApplyInsets();
    }
    private void folderCard() {
        LinearLayout c=card();c.addView(heading(l("Biblioteka muzyki","Music library")));c.addView(text(library==null?l("Wskaż folder z muzyką","Choose a music folder"):library.root.name+" · "+library.tracks.size()+l(" utworów"," tracks"),15,MUTED));
        buttons(c,button(l("Wybierz folder","Choose folder"),()->guard(()->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);if(!tree.isEmpty())intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(tree));startActivityForResult(intent,TREE);})),button(l("Odśwież","Refresh"),this::scan));
        c.addView(check(l("Wczytaj podkatalogi","Include subfolders"),recursive,b->{recursive=b;savePreferences();}));c.addView(check(l("Odczytaj tagi i czas (wolniejsze)","Read tags and duration (slower)"),tags,b->{tags=b;savePreferences();}));
        c.addView(text(l("Po zmianie opcji skanowania wybierz Odśwież.","Refresh after changing scan options."),12,MUTED));
    }
    private void renderCreate() {
        folderCard();LinearLayout c=card();c.addView(heading(l("Ustawienia playlist","Playlist settings")));
        choice(c,l("Miejsce zapisu","Save location"),new String[]{l("Osobno w albumach / folderach","One per album / folder"),l("Jedna w katalogu głównym","One in the root folder"),l("Jedna w podkatalogu","One in a subfolder")},mode,i->{mode=i;render();});
        if(mode==0)c.addView(text(l("Nazwa playlisty = nazwa folderu / albumu","Playlist name = folder / album name"),14,GREEN));else{c.addView(input(l("Nazwa playlisty","Playlist name"),playlistName,s->{playlistName=s;savePreferences();}));if(mode==2)c.addView(input(l("Podkatalog docelowy","Destination subfolder"),subfolder,s->{subfolder=s;savePreferences();}));}
        choice(c,l("Format zapisu","Playlist format"),PlaylistCodec.FORMATS,format,i->format=i);
        choice(c,l("Istniejące playlisty","Existing playlists"),new String[]{l("Pomiń istniejące","Skip existing"),l("Aktualizuj z kopią .bak","Update with .bak backup"),l("Nowa wersja (2), (3)…","New version (2), (3)…")},policy,i->policy=i);
        choice(c,l("Kolejność utworów","Track order"),new String[]{l("Nazwa pliku (naturalnie)","File name (natural)"),l("Wykonawca i tytuł","Artist and title")},sort,i->sort=i);
        c.addView(check(l("Ścieżki względne (przenośne)","Relative paths (portable)"),relative,b->{relative=b;savePreferences();}));
        c.addView(text(l("Formaty muzyki","Audio extensions"),13,MUTED));LinearLayout r=null;int n=0;for(String ext:PlaylistCodec.AUDIO){if(n++%3==0){r=row();c.addView(r);}CheckBox cb=check(ext.toUpperCase(Locale.ROOT),extensions.contains(ext),b->{if(b)extensions.add(ext);else extensions.remove(ext);savePreferences();});r.addView(cb,new LinearLayout.LayoutParams(0,-2,1));}
        LinearLayout albums=card();albums.addView(heading(l("Albumy i foldery","Albums and folders")));search(albums);buttons(albums,button(l("Wszystkie","Select all"),()->{if(library!=null)for(Storage.Entry e:library.folders)if(matches(e.path))selected.add(e.uri);drawAlbums();}),button(l("Żaden","Select none"),()->{if(library!=null)for(Storage.Entry e:library.folders)if(matches(e.path))selected.remove(e.uri);drawAlbums();}));contentRows=column();albums.addView(contentRows);drawAlbums();
        buttons(body,button(l("Podgląd przed zapisem","Preview before saving"),this::previewPlans));
    }
    private void search(LinearLayout parent) {EditText e=input(l("Szukaj…","Search…"),query,s->{query=s;if(tab==0)drawAlbums();else if(tab==1)drawSaved();else drawEditor();});parent.addView(e);}
    private boolean matches(String value){return value.toLowerCase(english?Locale.ENGLISH:Locale.forLanguageTag("pl")).contains(query.toLowerCase(english?Locale.ENGLISH:Locale.forLanguageTag("pl")));}
    private void drawAlbums() {
        if(contentRows==null)return;contentRows.removeAllViews();if(library==null){contentRows.addView(text(l("Wybierz folder, aby zobaczyć albumy.","Choose a folder to see albums."),14,MUTED));return;}
        Map<String,List<Track>> groups=groups();int shown=0;
        for(Storage.Entry folder:library.folders) {List<Track> tracks=groups.get(folder.path);if(tracks==null || tracks.isEmpty() || !matches(folder.path+" "+folder.name))continue;
            LinearLayout r=row();CheckBox cb=check(folder.name+" · "+tracks.size(),selected.contains(folder.uri),b->{if(b)selected.add(folder.uri);else selected.remove(folder.uri);});r.addView(cb,new LinearLayout.LayoutParams(0,-2,1));r.addView(button(l("Zobacz","View"),()->showTracks(folder.name,tracks)),new LinearLayout.LayoutParams(dp(80),dp(52)));contentRows.addView(r);contentRows.addView(text(folder.path.isEmpty()?"/":folder.path,12,MUTED));if(++shown>=300){contentRows.addView(text(l("Użyj wyszukiwania, aby zawęzić listę.","Use search to narrow the list."),12,MUTED));break;}
        }
        if(shown==0)contentRows.addView(text(l("Brak pasujących albumów.","No matching albums."),14,MUTED));
    }
    private Map<String,List<Track>> groups() {Map<String,List<Track>> map=new LinkedHashMap<>();if(library!=null)for(Track t:library.tracks){int i=t.path.lastIndexOf('/');String parent=i<0?"":t.path.substring(0,i);map.computeIfAbsent(parent,k->new ArrayList<>()).add(t);}return map;}
    private void renderSaved() {
        folderCard();LinearLayout c=card();c.addView(heading(l("Zapisane playlisty","Saved playlists")));c.addView(text(l("Zmiana nazwy i usuwanie dotyczą tylko plików playlist.","Rename and delete affect playlist files only."),13,MUTED));search(c);contentRows=column();c.addView(contentRows);drawSaved();
    }
    private void drawSaved() {
        if(contentRows==null)return;contentRows.removeAllViews();if(library==null)return;int n=0;
        for(Storage.Entry e:library.playlists)if(matches(e.path)) {LinearLayout box=column();box.addView(text(e.name,17,INK));box.addView(text(e.parentPath.isEmpty()?"/":e.parentPath,12,MUTED));buttons(box,button(l("Otwórz","Open"),()->guard(()->open(e))),button(l("Zmień nazwę","Rename"),()->rename(e)),button(l("Usuń","Delete"),()->delete(e)));contentRows.addView(box);if(++n>=200)break;}
        if(n==0)contentRows.addView(text(l("Brak zapisanych playlist.","No saved playlists."),14,MUTED));
    }
    private void renderEditor() {
        LinearLayout c=card();c.addView(heading(l("Edytor i podgląd","Editor and preview")+(dirty?" *":"")));
        c.addView(text(editor.size()+l(" utworów • "," tracks • ")+(opened==null?l("nowa lista","new playlist"):opened.name),13,MUTED));
        buttons(c,button(l("Nowa","New"),()->guard(()->{editor.clear();opened=null;importedRaw="";undo.clear();redo.clear();dirty=false;focused=-1;playlistName=l("Moja playlista","My playlist");persistDraft();render();})),button(l("Otwórz plik","Open file"),()->guard(()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,OPEN);})),button(l("Dodaj utwory","Add tracks"),()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,ADD);}));
        c.addView(input(l("Nazwa playlisty","Playlist name"),playlistName,s->{if(!s.equals(playlistName)){playlistName=s;dirty=true;}}));
        choice(c,l("Format zapisu","Playlist format"),PlaylistCodec.FORMATS,format,i->{format=i;dirty=true;});c.addView(check(l("Ścieżki względne","Relative paths"),relative,b->{relative=b;dirty=true;}));
        buttons(c,button(l("Zapisz w bibliotece","Save in library"),()->saveEditor(null)),button(l("Eksportuj jako…","Export as…"),this::export));
        c.addView(text(l("Względne ścieżki najlepiej współpracują z odtwarzaczami. Zewnętrzne dokumenty mogą używać content:// — ich obsługa zależy od odtwarzacza.","Relative paths offer the best player compatibility. External documents may use content:// references; support depends on the player."),12,MUTED));
        buttons(c,button(l("Cofnij","Undo"),()->history(undo,redo)),button(l("Ponów","Redo"),()->history(redo,undo)),button(l("Podgląd","Preview"),this::previewEditor));
        buttons(c,button(l("Sprawdź","Check"),this::validateEditor),button(l("Sortuj","Sort"),()->{checkpoint();order(editor);changed();}),button(l("Bez duplikatów","Deduplicate"),()->{checkpoint();Set<String> seen=new HashSet<>();editor.removeIf(t->!seen.add(identity(t)));changed();}));
        buttons(c,button(l("Usuń brakujące wpisy","Remove missing entries"),this::removeMissing),button(l("Napraw ścieżki","Repair paths"),this::repairPaths));
        if(!importedRaw.isEmpty())c.addView(text(l("Import zachowuje utwory, tytuły i czasy. Zapis odbudowuje plik; komentarze i niestandardowe metadane mogą zostać pominięte. Aktualizacja tworzy kopię .bak.","Import keeps tracks, titles and durations. Saving rebuilds the file; comments and custom metadata may be omitted. Updating creates a .bak backup."),12,MUTED));
        LinearLayout rows=card();search(rows);contentRows=column();rows.addView(contentRows);drawEditor();
    }
    private void drawEditor() {
        if(contentRows==null)return;contentRows.removeAllViews();int shown=0;
        for(int i=0;i<editor.size();i++) {Track t=editor.get(i);if(!matches(t.label()+" "+t.artist+" "+t.location))continue;final int index=i;
            LinearLayout box=column();box.setPadding(dp(8),dp(6),dp(8),dp(8));if(index==focused)box.setBackground(shape(0xffe0eee6,10));
            TextView label=text((i+1)+". "+t.label()+(t.missing?l("  [brak]","  [missing]"):""),16,INK);label.setOnClickListener(v->{focused=index;drawEditor();});box.addView(label);TextView path=text(t.location,11,MUTED);path.setMaxLines(2);box.addView(path);
            if(!t.artist.isEmpty() || t.duration>=0)box.addView(text(t.artist+(t.duration>=0?" · "+(int)t.duration/60+":"+String.format(Locale.ROOT,"%02d",(int)t.duration%60):""),12,MUTED));
            if(index==focused)buttons(box,button("↑",()->move(index,-1)),button("↓",()->move(index,1)),button(l("Usuń wpis","Remove entry"),()->{checkpoint();editor.remove(index);focused=Math.min(index,editor.size()-1);changed();}));
            contentRows.addView(box);if(++shown>=250){contentRows.addView(text(l("Użyj wyszukiwania, aby zawęzić listę.","Use search to narrow the list."),12,MUTED));break;}
        }
        if(shown==0)contentRows.addView(text(l("Brak utworów. Dodaj pliki muzyczne.","No tracks. Add music files."),14,MUTED));
    }
    private void move(int i,int delta){int j=i+delta;if(j<0 || j>=editor.size())return;checkpoint();Collections.swap(editor,i,j);focused=j;changed();}
    private void checkpoint(){undo.addLast(copy(editor));while(undo.size()>30)undo.removeFirst();redo.clear();}
    private void changed(){dirty=true;persistDraft();render();}
    private void history(Deque<List<Track>> from,Deque<List<Track>> to){if(from.isEmpty())return;to.addLast(copy(editor));editor=from.removeLast();focused=-1;changed();}
    private void order(List<Track> tracks){tracks.sort((a,b)->PlaylistCodec.NATURAL.compare(sort==0?PlaylistCodec.basename(a.location):a.artist+" "+a.label(),sort==0?PlaylistCodec.basename(b.location):b.artist+" "+b.label()));}
    private String identity(Track t){return t.location;}
    private void guard(Runnable action) {
        if(!dirty){action.run();return;}
        new AlertDialog.Builder(this).setTitle(l("Niezapisane zmiany","Unsaved changes")).setMessage(l("Zapisać zmiany w edytorze?","Save changes in the editor?"))
            .setPositiveButton(l("Zapisz","Save"),(d,w)->saveEditor(action)).setNegativeButton(l("Odrzuć","Discard"),(d,w)->{dirty=false;editor.clear();opened=null;importedRaw="";undo.clear();redo.clear();focused=-1;persistDraft();action.run();}).setNeutralButton(l("Anuluj","Cancel"),null).show();
    }
    // Android 13+ uses the platform back dispatcher registered in onCreate; older devices use this fallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){handleBack();}
    private void handleBack(){if(busy){cancel.set(true);return;}guard(()->{persistDraft();finish();});}
    private void error(Throwable ex){new AlertDialog.Builder(this).setTitle(l("Nie udało się wykonać operacji","Operation failed")).setMessage(ex.getMessage()==null?ex.toString():ex.getMessage()).setPositiveButton("OK",null).show();}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void showText(String title,String value){TextView t=text(value,14,INK);t.setPadding(dp(20),dp(12),dp(20),dp(12));t.setTextIsSelectable(true);ScrollView s=new ScrollView(this);s.addView(t);new AlertDialog.Builder(this).setTitle(title).setView(s).setPositiveButton("OK",null).show();}
    private void showTracks(String title,List<Track> tracks){StringBuilder b=new StringBuilder();int n=0;for(Track t:tracks){b.append(++n).append(". ").append(t.label()).append('\n');if(n>=1000){b.append("…");break;}}showText(title,b.toString());}
    private interface Work<T>{T run(AtomicBoolean stop) throws Exception;}
    private <T> void task(String label,Work<T> work,java.util.function.Consumer<T> done) {
        if(busy)return;busy=true;progress=label;cancel=new AtomicBoolean();AtomicBoolean stop=cancel;render();
        io.execute(()-> {T result=null;Exception failure=null;try{result=work.run(stop);}catch(Exception ex){failure=ex;}T r=result;Exception e=failure;
            ui.post(()->{if(isDestroyed())return;busy=false;progress="";if(e==null){done.accept(r);persistDraft();}else{report+="\n"+(e instanceof CancellationException?l("Anulowano. Zakończone zapisy pozostają na dysku.","Cancelled. Completed writes remain on disk."):e.toString());render();if(!(e instanceof CancellationException))error(e);} });
        });
    }
    private void setProgress(String value){ui.post(()->{if(progressLabel!=null)progressLabel.setText(value);});}
    private void scan() {
        if(tree.isEmpty()){toast(l("Najpierw wybierz folder.","Choose a folder first."));return;}
        final String root=tree;final boolean sub=recursive,meta=tags;Set<String> previous=new HashSet<>(selected);boolean keep=root.equals(prefs.getString("scannedTree",""));
        task(l("Skanowanie…","Scanning…"),stop->storage.scan(root,sub,meta,stop,this::setProgress),result->{library=result;selected.clear();for(Storage.Entry e:library.folders)if(!keep || previous.contains(e.uri))selected.add(e.uri);prefs.edit().putString("scannedTree",root).apply();String summary=l("Skanowanie: ","Scanned: ")+result.tracks.size()+l(" utworów, "," tracks, ")+result.playlists.size()+l(" playlist."," playlists.")+"\n"+String.join("\n",result.warnings);report=report+"\n"+summary;if(report.length()>200000)report=report.substring(report.length()-200000);render();});
    }
    private static class Plan {Storage.Entry folder;String name;List<Track> tracks;Plan(Storage.Entry f,String n,List<Track> t){folder=f;name=n;tracks=t;}}
    private List<Plan> plans() {
        if(library==null)throw new IllegalArgumentException(l("Wybierz i wczytaj folder.","Choose and scan a folder."));
        List<Plan> plans=new ArrayList<>();Map<String,List<Track>> groups=groups();List<Track> combined=new ArrayList<>();String extension="."+PlaylistCodec.FORMATS[format].toLowerCase(Locale.ROOT);
        for(Storage.Entry folder:library.folders)if(selected.contains(folder.uri)) {List<Track> tracks=new ArrayList<>();for(Track t:groups.getOrDefault(folder.path,Collections.emptyList()))if(extensions.contains(PlaylistCodec.ext(t.path)))tracks.add(t.copy());if(tracks.isEmpty())continue;order(tracks);if(mode==0){PlaylistCodec.name(folder.name);plans.add(new Plan(folder,folder.name+extension,tracks));}else combined.addAll(tracks);}
        if(mode!=0 && !combined.isEmpty()){String name=PlaylistCodec.name(playlistName)+extension;Storage.Entry folder=library.root;if(mode==2){String dir=PlaylistCodec.name(subfolder);folder=new Storage.Entry("",dir,dir,library.root.uri,"",true,0);}order(combined);plans.add(new Plan(folder,name,combined));}
        return plans;
    }
    private void previewPlans() {
        try {List<Plan> plans=plans();if(plans.isEmpty()){toast(l("Brak utworów dla wybranych albumów i formatów.","No tracks match the selected albums and formats."));return;}
            StringBuilder b=new StringBuilder();int total=0;for(Plan p:plans){b.append(Storage.join(p.folder.path,p.name)).append(" · ").append(p.tracks.size()).append('\n');total+=p.tracks.size();}
            b.append('\n').append(total).append(l(" utworów.\nUkryte przez wyszukiwanie, zaznaczone albumy również są uwzględniane."," tracks.\nSelected albums hidden by search are also included."));
            new AlertDialog.Builder(this).setTitle(l("Podgląd przed zapisem","Preview before saving")).setMessage(b.toString()).setPositiveButton(l("Utwórz playlisty","Create playlists"),(d,w)->generate(plans)).setNegativeButton(l("Anuluj","Cancel"),null).show();
        }catch(Exception ex){error(ex);}
    }
    private void generate(List<Plan> plans) {
        final String fmt=PlaylistCodec.FORMATS[format];final boolean rel=relative;final int existingPolicy=policy;final Storage.Library snapshot=library;
        report=l("Tworzenie playlist:\n","Creating playlists:\n");
        task(l("Zapisywanie…","Saving…"),stop->{List<String> lines=new ArrayList<>();for(Plan p:plans){Storage.check(stop);Storage.Entry parent=p.folder;if(parent.uri.isEmpty())parent=storage.subfolder(snapshot.root,parent.name,stop);setProgress(p.name);
            try {String content=PlaylistCodec.serialize(p.tracks,fmt,PlaylistCodec.stem(p.name),parent.path,rel);String line=storage.save(parent,p.name,content.getBytes(StandardCharsets.UTF_8),existingPolicy,stop);lines.add(line);ui.post(()->report+=line+"\n");}catch(CancellationException ex){throw ex;}catch(Exception ex){String line="ERROR "+p.name+": "+ex.getMessage();lines.add(line);ui.post(()->report+=line+"\n");}}
            return String.join("\n",lines);},result->{report=result;render();showText(l("Wynik zapisu","Save results"),result);scan();});
    }
    private void open(Storage.Entry e) {
        final Storage.Library snapshot=library;
        task(l("Odczytywanie playlisty…","Reading playlist…"),stop->{Storage.check(stop);String text=PlaylistCodec.decode(storage.read(e.uri));List<Track> rows=storage.resolve(PlaylistCodec.parse(text,PlaylistCodec.ext(e.name)),e,snapshot);return new Object[]{text,rows};},result->{
            importedRaw=(String)result[0];@SuppressWarnings("unchecked")List<Track> rows=(List<Track>)result[1];editor=rows;opened=e;playlistName=PlaylistCodec.stem(e.name);format=Arrays.asList(PlaylistCodec.FORMATS).indexOf(PlaylistCodec.ext(e.name).toUpperCase(Locale.ROOT));dirty=false;undo.clear();redo.clear();tab=2;focused=-1;query="";render();
        });
    }
    private void rename(Storage.Entry entry) {
        EditText name=input(l("Nowa nazwa bez rozszerzenia","New name without extension"),PlaylistCodec.stem(entry.name),s->{});
        new AlertDialog.Builder(this).setTitle(l("Zmień nazwę playlisty","Rename playlist")).setView(name).setPositiveButton(l("Zmień nazwę","Rename"),(d,w)->task(l("Zmiana nazwy…","Renaming…"),stop->storage.rename(entry,name.getText().toString(),stop),e->{if(opened!=null && opened.uri.equals(entry.uri)){opened=e;playlistName=PlaylistCodec.stem(e.name);}render();scan();})).setNegativeButton(l("Anuluj","Cancel"),null).show();
    }
    private void delete(Storage.Entry entry) {
        new AlertDialog.Builder(this).setTitle(l("Usunąć playlistę?","Delete playlist?"))
            .setMessage(entry.name+"\n\n"+l("Plik playlisty zostanie trwale usunięty. Muzyka pozostanie bez zmian.","The playlist file will be permanently deleted. Music files remain unchanged."))
            .setPositiveButton(l("Usuń playlistę","Delete playlist"),(d,w)->task(l("Usuwanie…","Deleting…"),stop->{Storage.check(stop);storage.deletePlaylist(entry);return entry;},e->{if(opened!=null && opened.uri.equals(e.uri)){opened=null;dirty=true;}render();scan();})).setNegativeButton(l("Anuluj","Cancel"),null).show();
    }
    private Storage.Entry editorFolder(){if(opened!=null && !opened.parentUri.isEmpty())return new Storage.Entry(opened.parentUri,"",opened.parentPath,"","",true,0);return library==null?null:library.root;}
    private void saveEditor(Runnable continuation) {
        if(library==null && editorFolder()==null){toast(l("Wybierz folder w zakładce Tworzenie lub użyj Eksportuj jako.","Choose a folder in Create or use Export as."));return;}
        Storage.Entry folder=editorFolder();String name;try{name=PlaylistCodec.name(playlistName)+"."+PlaylistCodec.FORMATS[format].toLowerCase(Locale.ROOT);}catch(Exception ex){error(ex);return;}
        String finalName=name;List<Track> rows=copy(editor);String fmt=PlaylistCodec.FORMATS[format];boolean rel=relative;
        Runnable save=()->task(l("Zapisywanie playlisty…","Saving playlist…"),stop->{String value=PlaylistCodec.serialize(rows,fmt,PlaylistCodec.stem(finalName),folder.path,rel);String result=storage.save(folder,finalName,value.getBytes(StandardCharsets.UTF_8),1,stop);Storage.Entry saved=storage.find(folder,finalName,new AtomicBoolean());return new Object[]{result,saved};},result->{opened=(Storage.Entry)result[1];dirty=false;importedRaw="";report=(String)result[0];if(library!=null && opened!=null && library.folders.stream().anyMatch(f->f.uri.equals(folder.uri))){Storage.Entry saved=opened;library.playlists.removeIf(e->e.uri.equals(saved.uri) || (e.parentUri.equals(saved.parentUri) && e.name.equals(saved.name)));library.playlists.add(saved);library.playlists.sort((a,b)->PlaylistCodec.NATURAL.compare(a.path,b.path));}render();toast(l("Zapisano playlistę.","Playlist saved."));if(continuation!=null)continuation.run();});
        new AlertDialog.Builder(this).setTitle(l("Zapisz playlistę","Save playlist")).setMessage(Storage.join(folder.path,name)+"\n\n"+l("Istniejący plik otrzyma kopię .bak. Plik jest odbudowywany z wpisów edytora; dodatkowe metadane importowanego pliku mogą zostać pominięte.","An existing file receives a .bak backup. The file is rebuilt from editor entries; imported custom metadata may be omitted."))
            .setPositiveButton(l("Zapisz","Save"),(d,w)->save.run()).setNegativeButton(l("Anuluj","Cancel"),null).show();
    }
    private void previewEditor(){try{Storage.Entry folder=editorFolder();showText(l("Podgląd pliku","File preview"),PlaylistCodec.serialize(editor,PlaylistCodec.FORMATS[format],playlistName,folder==null?"":folder.path,relative));}catch(Exception ex){error(ex);}}
    private void export() {
        try{String base=PlaylistCodec.name(playlistName);exportName=base+"."+PlaylistCodec.FORMATS[format].toLowerCase(Locale.ROOT);
            // Export can target any provider: absolute/content references avoid guessing the chosen folder.
            exportBytes=PlaylistCodec.serialize(copy(editor),PlaylistCodec.FORMATS[format],base,"",false).getBytes(StandardCharsets.UTF_8);
            new AlertDialog.Builder(this).setTitle(l("Eksport playlisty","Export playlist")).setMessage(l("Eksport zapisuje nowy plik z bezwzględnymi ścieżkami lub adresami content://. Dla przenośnej listy użyj Zapisz w bibliotece. Komentarze i dodatkowe metadane importu nie są zachowywane.","Export creates a new file with absolute paths or content:// references. For portable relative paths, use Save in library. Imported comments and custom metadata are not preserved."))
                .setPositiveButton(l("Wybierz miejsce","Choose destination"),(d,w)->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.setType("application/octet-stream");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_TITLE,exportName);startActivityForResult(i,EXPORT);}).setNegativeButton(l("Anuluj","Cancel"),null).show();
        }catch(Exception ex){error(ex);}
    }
    private void validateEditor() {
        List<Track> rows=copy(editor);task(l("Sprawdzanie…","Checking…"),stop->{Set<String> seen=new HashSet<>();int duplicates=0,missing=0;for(Track t:rows){Storage.check(stop);if(!seen.add(identity(t)))duplicates++;if(!t.uri.isEmpty()){try{storage.describe(Uri.parse(t.uri));t.missing=false;}catch(Exception ex){t.missing=true;}}if(t.missing)missing++;}return new Object[]{rows,duplicates,missing};},result->{checkpoint();@SuppressWarnings("unchecked")List<Track> checked=(List<Track>)result[0];editor=checked;report=l("Utwory: ","Tracks: ")+editor.size()+l("\nDuplikaty: ","\nDuplicates: ")+result[1]+l("\nBrakujące lub poza dostępem: ","\nMissing or inaccessible: ")+result[2];render();showText(l("Kontrola playlisty","Playlist check"),report);});
    }
    private void removeMissing() {
        List<Track> rows=copy(editor);
        task(l("Sprawdzanie dostępności…","Checking availability…"),stop->{for(Track t:rows){Storage.check(stop);if(!t.uri.isEmpty()){try{storage.describe(Uri.parse(t.uri));t.missing=false;}catch(Exception ex){t.missing=true;}}}return rows;},result->{
            int count=0;for(Track t:result)if(t.missing)count++;final int missing=count;
            new AlertDialog.Builder(this).setTitle(l("Usuń brakujące wpisy","Remove missing entries")).setMessage(missing+l(" wpisów niedostępnych. Usunąć je z edytora? Pliki muzyczne nie zostaną usunięte."," unavailable entries. Remove them from the editor? Music files will not be deleted."))
                .setPositiveButton(l("Usuń wpisy","Remove entries"),(d,w)->{checkpoint();editor=result;editor.removeIf(t->t.missing);focused=-1;changed();}).setNegativeButton(l("Anuluj","Cancel"),null).show();render();
        });
    }
    private void repairPaths() {
        if(library==null){toast(l("Najpierw wczytaj nowy folder z muzyką.","Scan the new music folder first."));return;}
        EditText old=input(l("Stary katalog, np. /storage/emulated/0/OldMusic","Old folder, e.g. /storage/emulated/0/OldMusic"),"",s->{});
        new AlertDialog.Builder(this).setTitle(l("Napraw ścieżki","Repair paths")).setMessage(l("Wpisz stary prefiks ścieżek. Nowym katalogiem jest wybrana biblioteka: ","Enter the old path prefix. The new folder is the selected library: ")+library.root.name).setView(old)
            .setPositiveButton(l("Napraw","Repair"),(d,w)->{String prefix=PlaylistCodec.normalize(old.getText().toString().trim());if(prefix.isEmpty() || prefix.equals("/")){toast(l("Podaj pełny stary katalog.","Enter the full old folder."));return;}
                List<Track> rows=copy(editor);Storage.Library snapshot=library;
                task(l("Naprawianie…","Repairing…"),stop->{int repaired=0;for(Track t:rows){Storage.check(stop);boolean available=false;if(!t.uri.isEmpty())try{storage.describe(Uri.parse(t.uri));available=true;}catch(Exception ignored){}
                    String path=PlaylistCodec.normalize(t.location);if(!available && path.startsWith(prefix+"/")){String suffix=path.substring(prefix.length()+1);Track replacement=snapshot.byPath.get(suffix);if(replacement!=null){t.uri=replacement.uri;t.location=replacement.location;t.path=replacement.path;t.missing=false;repaired++;}}}return new Object[]{rows,repaired};},result->{checkpoint();@SuppressWarnings("unchecked")List<Track> repaired=(List<Track>)result[0];editor=repaired;dirty=true;report=l("Naprawione ścieżki: ","Repaired paths: ")+result[1];render();showText(l("Naprawa ścieżek","Path repair"),report);});
            }).setNegativeButton(l("Anuluj","Cancel"),null).show();
    }
    private void retain(Intent result,Uri uri) {int flags=0;if((result.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0)flags|=Intent.FLAG_GRANT_READ_URI_PERMISSION;if((result.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0)flags|=Intent.FLAG_GRANT_WRITE_URI_PERMISSION;try{if(flags!=0)getContentResolver().takePersistableUriPermission(uri,flags);}catch(SecurityException ex){toast(l("Dostęp może być tymczasowy; wybierz plik ponownie po restarcie.","Access may be temporary; select the file again after restart."));}}
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(result!=RESULT_OK || data==null)return;
        Uri uri=data.getData();
        if(request==TREE && uri!=null){retain(data,uri);if(!tree.equals(uri.toString())){for(Track t:editor)t.path="";opened=null;}tree=uri.toString();library=null;selected.clear();savePreferences();scan();}
        else if(request==OPEN && uri!=null){retain(data,uri);task(l("Otwieranie…","Opening…"),stop->{Storage.Entry e=storage.describe(uri);if(!PlaylistCodec.isPlaylist(e.name))throw new IOException(l("Wybierz M3U8, M3U, PLS, XSPF lub WPL.","Choose M3U8, M3U, PLS, XSPF or WPL."));if(library!=null)for(Storage.Entry f:library.playlists)if(f.uri.equals(e.uri) || Storage.localPath(f.uri).equals(Storage.localPath(e.uri)))return f;return e;},this::open);}
        else if(request==ADD){List<Uri> uris=new ArrayList<>();if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());else if(uri!=null)uris.add(uri);for(Uri u:uris)retain(data,u);
            final Storage.Library snapshot=library;final boolean readTags=tags;task(l("Dodawanie utworów…","Adding tracks…"),stop->{List<Track> rows=new ArrayList<>();for(Uri u:uris){Storage.check(stop);Storage.Entry e=storage.describe(u);if(!PlaylistCodec.AUDIO.contains(PlaylistCodec.ext(e.name)))continue;Track t=storage.track(e);if(snapshot!=null){Track known=snapshot.byLocation.get(t.location);if(known!=null)t=known.copy();}if(readTags)storage.readTags(t);rows.add(t);}return rows;},rows->{checkpoint();Set<String> seen=new HashSet<>();for(Track t:editor)seen.add(identity(t));for(Track t:rows)if(seen.add(identity(t)))editor.add(t);dirty=true;tab=2;query="";render();toast(rows.isEmpty()?l("Nie wybrano obsługiwanych plików audio.","No supported audio files selected."):l("Dodano utwory.","Tracks added."));});
        }else if(request==EXPORT && uri!=null){byte[] bytes=exportBytes;if(bytes==null){error(new IOException(l("Eksport przerwany. Wybierz Eksportuj ponownie.","Export interrupted. Choose Export again.")));return;}task(l("Eksportowanie…","Exporting…"),stop->{Storage.check(stop);storage.write(uri.toString(),bytes);return uri;},saved->{exportBytes=null;report=l("Wyeksportowano: ","Exported: ")+exportName;render();toast(report);});}
    }
    private void about(){showText("Studio playlist · Android 1.0.0",l("Lokalny edytor i generator playlist.\n\nMuzyka: MP3, FLAC, WAV, M4A, AAC, OGG, OPUS, WMA, AIFF, AIF, APE, ALAC.\nPlaylisty: M3U8, M3U, PLS, XSPF, WPL.\n\nDostęp tylko do wybranych folderów i plików. Brak reklam, analityki i kont. Ustawienia oraz szkic są przechowywane lokalnie. Tagi muzyczne są tylko odczytywane. Usunięcie wpisu z listy nie usuwa muzyki. Usunięcie zapisanej playlisty jest trwałe i wymaga potwierdzenia.\n\nFormaty M3U/M3U8 są zapisywane w UTF-8; odczyt starszych plików obsługuje Windows-1250. Import nie zachowuje wszystkich niestandardowych metadanych. Ścieżki content:// mogą nie działać w innych odtwarzaczach.\n\nMarek Zettel - zetmar · MIT\nhttps://github.com/zetmar-collab/Listy","Local playlist editor and generator.\n\nAudio: MP3, FLAC, WAV, M4A, AAC, OGG, OPUS, WMA, AIFF, AIF, APE, ALAC.\nPlaylists: M3U8, M3U, PLS, XSPF, WPL.\n\nAccess is limited to files and folders you choose. No advertising, analytics or accounts. Settings and draft stay on your device. Audio tags are read only. Removing a list entry does not delete music. Deleting a saved playlist is permanent and requires confirmation.\n\nM3U/M3U8 are written in UTF-8; legacy reading supports Windows-1250. Import does not preserve all custom metadata. content:// references may not work in other players.\n\nMarek Zettel - zetmar · MIT\nhttps://github.com/zetmar-collab/Listy"));}
}
