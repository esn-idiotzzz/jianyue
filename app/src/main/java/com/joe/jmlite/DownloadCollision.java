package com.joe.jmlite;

import android.app.Activity;
import android.content.Intent;
import org.json.JSONObject;

public final class DownloadCollision {
    public static void openLocal(Activity activity,JSONObject book){Runnable open=()->activity.startActivity(new Intent(activity,MihonEntryActivity.class).putExtra("library_key",Library.key(book)).putExtra("title",book.optString("title")));if(book.optBoolean("privacy"))PrivacyVault.enter(activity,open);else open.run();}
    public static void show(Activity activity,JSONObject book){PredictiveBack.dialog(activity).setTitle("书架已有此书籍，是否跳转其位置？")
        .setNegativeButton("取消",null).setPositiveButton("跳转",(d,n)->{
            Runnable jump=()->activity.startActivity(new Intent(activity,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("shelf_jump",Library.key(book)).putExtra("shelf_highlight",Library.key(book)).putExtra("shelf_private",book.optBoolean("privacy")));
            if(book.optBoolean("privacy"))PrivacyVault.enter(activity,jump);else jump.run();
        }).show();}
}
