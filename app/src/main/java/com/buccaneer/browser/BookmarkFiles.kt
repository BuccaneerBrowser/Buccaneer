package com.buccaneer.browser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream

internal object BookmarkFiles {
    fun read(input: InputStream): String = input.use {
        val out=java.io.ByteArrayOutputStream();val chunk=ByteArray(4096)
        while(true){val n=it.read(chunk);if(n<0)break;if(out.size()+n>1024*1024)throw java.io.IOException("Backup exceeds 1 MiB");out.write(chunk,0,n)}
        out.toString("UTF-8")
    }
    /** Public Downloads, never silently an uninstall-deleted app folder. */
    fun export(context: Context, json: String): String {
        val app=context.applicationContext
        if(Build.VERSION.SDK_INT>=29){
            val filename="Buccaneer-bookmarks-${System.currentTimeMillis()}.json"
            val values=ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME,filename)
                put(MediaStore.MediaColumns.MIME_TYPE,"application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING,1)
            }
            val resolver=app.contentResolver
            val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)
                ?:throw java.io.IOException("Public Downloads provider unavailable; choose a document location")
            try {
                resolver.openOutputStream(uri,"w")?.use {it.write(json.toByteArray(Charsets.UTF_8));it.flush()}
                    ?:throw java.io.IOException("Cannot write public backup")
                val ready=ContentValues().apply{put(MediaStore.MediaColumns.IS_PENDING,0)}
                if(resolver.update(uri,ready,null,null)<=0)throw java.io.IOException("Cannot publish public backup")
                app.getSharedPreferences("settings",Context.MODE_PRIVATE).edit().putString("bookmarkBackupUri",uri.toString()).apply()
            }catch(e:Throwable){try{resolver.delete(uri,null,null)}catch(_:Throwable){};throw e}
            return "Exported to Downloads/$filename (survives uninstall)"
        }
        @Suppress("DEPRECATION") val dir=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if(!dir.exists()&&!dir.mkdirs())throw java.io.IOException("Cannot create Downloads")
        val target=File(dir,"bookmarks.json");val temp=File(dir,"bookmarks.json.tmp")
        temp.outputStream().use{it.write(json.toByteArray(Charsets.UTF_8));it.flush()}
        if(!temp.renameTo(target)){temp.delete();throw java.io.IOException("Cannot replace backup")}
        return "Exported to Downloads/bookmarks.json (survives uninstall)"
    }
    fun readSaved(context: Context): String {
        if(Build.VERSION.SDK_INT>=29){
            val value=context.getSharedPreferences("settings",Context.MODE_PRIVATE).getString("bookmarkBackupUri",null)
                ?:throw java.io.IOException("Choose a backup, or use a file manager's Open with Buccaneer")
            return read(context.contentResolver.openInputStream(Uri.parse(value))?:throw java.io.IOException("Backup unavailable"))
        }
        @Suppress("DEPRECATION") val file=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"bookmarks.json")
        return read(file.inputStream())
    }
}
