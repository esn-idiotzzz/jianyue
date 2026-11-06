package com.joe.jmlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.Gravity;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/** A process-session privacy gate; it does not encrypt comic media. */
public final class PrivacyVault {
    private static boolean unlocked;
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("privacy_shelf",0);}
    public static void enter(Activity a,Runnable done){if(unlocked||prefs(a).getString("hash","").isEmpty()){unlocked=true;done.run();}else keypad(a,false,done);}
    public static void settings(Activity a){enter(a,()->keypad(a,true,()->{}));}
    public static void beforeFirstMove(Activity a,Runnable move){
        if(prefs(a).getBoolean("introduced",false)){move.run();return;}
        PredictiveBack.dialog(a).setTitle("隐私书架").setMessage("在书架界面下拉进入隐私书架，是否设置隐私密码？后续可自行点击仓库界面右上角小字设置")
            .setNegativeButton("暂不设置",(d,n)->{save(a,"");prefs(a).edit().putBoolean("introduced",true).apply();move.run();})
            .setPositiveButton("设置密码",(d,n)->keypad(a,true,()->{prefs(a).edit().putBoolean("introduced",true).apply();move.run();})).show();
    }
    private static String digest(String salt,String pin){try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest((salt+":"+pin).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private static void save(Activity a,String pin){byte[] bytes=new byte[24];new SecureRandom().nextBytes(bytes);String salt=android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP);prefs(a).edit().putString("salt",salt).putString("hash",pin.isEmpty()?"":digest(salt,pin)).apply();unlocked=true;}
    private static void keypad(Activity a,boolean setting,Runnable done){
        Ui ui=new Ui(a);LinearLayout body=ui.column();body.setPadding(ui.dp(22),ui.dp(10),ui.dp(22),ui.dp(10));
        EditText field=new EditText(a);field.setSingleLine(true);field.setGravity(Gravity.CENTER);field.setTextSize(17);field.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);field.setShowSoftInputOnFocus(false);field.setHint(setting?"不输入就是无隐私密码":"请输入4位隐私密码");body.addView(field,new LinearLayout.LayoutParams(-1,ui.dp(60)));
        TextView error=ui.text("",13,0xffd84343,false);error.setGravity(Gravity.CENTER);body.addView(error);
        for(int row=0;row<4;row++){LinearLayout line=ui.row();for(int col=0;col<3;col++){String value=row<3?String.valueOf(row*3+col+1):col==0?"清空":col==1?"0":"⌫";TextView key=ui.button(value,false);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ui.dp(52),1);lp.setMargins(ui.dp(3),ui.dp(3),ui.dp(3),ui.dp(3));line.addView(key,lp);key.setOnClickListener(v->{String text=field.getText().toString();field.setText(value.equals("清空")?"":value.equals("⌫")?(text.isEmpty()?"":text.substring(0,text.length()-1)):text.length()<4?text+value:text);field.setSelection(field.length());error.setText("");});}body.addView(line);}
        String[] first={null};AlertDialog dialog=PredictiveBack.dialog(a).setTitle(setting?"设置隐私密码":"隐私书架").setView(body).setNegativeButton("取消",null).setPositiveButton(setting?"继续":"解锁",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{String pin=field.getText().toString();if(setting&&first[0]==null&&pin.isEmpty()){save(a,"");dialog.dismiss();done.run();return;}if(!pin.matches("[0-9]{4}")){error.setText("请输入4位数字");return;}if(setting){if(first[0]==null){first[0]=pin;field.setText("");field.setHint("再次输入4位密码");dialog.getButton(-1).setText("确认密码");return;}if(!first[0].equals(pin)){error.setText("两次密码不一致，请重新确认");field.setText("");return;}save(a,pin);}else if(!MessageDigest.isEqual(digest(prefs(a).getString("salt",""),pin).getBytes(StandardCharsets.UTF_8),prefs(a).getString("hash","").getBytes(StandardCharsets.UTF_8))){error.setText("密码错误");field.setText("");return;}else unlocked=true;dialog.dismiss();done.run();}));dialog.show();
    }
}
