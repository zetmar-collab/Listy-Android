package pl.zetmar.studioplaylist;

import static com.google.common.truth.Truth.assertThat;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.*;

@RunWith(AndroidJUnit4.class)
public class ActivityInstrumentedTest {
    private Instrumentation instrumentation;private MainActivity activity;
    @Before public void start() {
        instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        context.getSharedPreferences("listy",Context.MODE_PRIVATE).edit().clear().putBoolean("english",false).commit();
        new File(context.getFilesDir(),"draft.json").delete();
        Intent i=new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity=(MainActivity)instrumentation.startActivitySync(i);instrumentation.waitForIdleSync();
    }
    @After public void finish(){if(activity!=null)instrumentation.runOnMainSync(()->activity.finish());instrumentation.waitForIdleSync();}
    private List<View> matching(String text) {ArrayList<View> views=new ArrayList<>();activity.getWindow().getDecorView().findViewsWithText(views,text,View.FIND_VIEWS_WITH_TEXT);return views;}
    private void tap(String label) {for(View v:matching(label))if(v instanceof Button && ((Button)v).getText().toString().equals(label)){v.performClick();return;}throw new AssertionError("Missing button: "+label);}
    @Test public void startsInPolishAndSwitchesToEnglish() {
        instrumentation.runOnMainSync(()->{assertThat(matching("Wybierz folder")).isNotEmpty();tap("EN");assertThat(matching("Choose folder")).isNotEmpty();assertThat(matching("Include subfolders")).isNotEmpty();});
    }
    @Test public void editorAndSavedTabsOpenAndRenderOnPhone() throws Exception {
        instrumentation.runOnMainSync(()->{tap("Edytor");assertThat(matching("Dodaj utwory")).isNotEmpty();assertThat(matching("Zapisz w bibliotece")).isNotEmpty();tap("Zapisane");assertThat(matching("Zapisane playlisty")).isNotEmpty();tap("Tworzenie");});
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(()->{View view=activity.getWindow().getDecorView();Bitmap bitmap=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));try(FileOutputStream out=new FileOutputStream(new File(activity.getExternalFilesDir(null),"android-create.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}catch(IOException ex){throw new RuntimeException(ex);}finally{bitmap.recycle();}});
    }
}
