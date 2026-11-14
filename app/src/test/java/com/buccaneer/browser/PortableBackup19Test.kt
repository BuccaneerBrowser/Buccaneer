package com.buccaneer.browser
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class PortableBackup19Test {
    class DownloadsProvider(val target:File, val reject:Boolean=false):ContentProvider(){
        var inserted:Uri?=null;var values:ContentValues?=null;var published=false;var deleted=false
        override fun onCreate()=true
        override fun insert(uri:Uri,v:ContentValues?):Uri? { inserted=uri;values=v?.let{ContentValues(it)};return if(reject)null else Uri.withAppendedPath(uri,"42") }
        override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?) :Int {published=v?.getAsInteger(MediaStore.MediaColumns.IS_PENDING)==0;return 1}
        override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int{deleted=true;target.delete();return 1}
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor = ParcelFileDescriptor.open(target,ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_READ_WRITE)
        override fun getType(uri:Uri)="application/json"
        override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor?=null
    }
    @Test fun modernExportUsesPublicDownloadsAndPublishesOnlyAfterWrite(){
        val app=RuntimeEnvironment.getApplication();val target=File(app.cacheDir,"public-backup-fixture.json")
        val p=DownloadsProvider(target);p.attachInfo(app,android.content.pm.ProviderInfo().apply{authority="media";exported=true});ShadowContentResolver.registerProviderInternal("media",p)
        val data="{\"items\":[]}";val result=BookmarkFiles.export(app,data)
        assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI,p.inserted)
        assertEquals("Download",p.values!!.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(1,p.values!!.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals(data,target.readText());assertTrue(p.published);assertFalse(p.deleted)
        assertTrue(result.contains(p.values!!.getAsString(MediaStore.MediaColumns.DISPLAY_NAME)))
        assertTrue(app.getSharedPreferences("settings",0).getString("bookmarkBackupUri","")!!.startsWith("content://media/"))
    }
    @Test fun unavailablePublicProviderDoesNotFallBackToAnUninstallDeletedAppFolder(){
        val app=RuntimeEnvironment.getApplication();val p=DownloadsProvider(File(app.cacheDir,"unused.json"),true)
        p.attachInfo(app,android.content.pm.ProviderInfo().apply{authority="media";exported=true});ShadowContentResolver.registerProviderInternal("media",p)
        try{BookmarkFiles.export(app,"{\"items\":[]}");fail("Must report portable export failure")}catch(e:java.io.IOException){assertTrue(e.message!!.contains("Public Downloads"))}
        assertFalse(p.published)
    }
}
