package com.officeledger.checkin;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final int IMPORT=1001,EXPORT=1002;
    private WebView web;
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        web=new WebView(this);setContentView(web);
        web.setBackgroundColor(0xfff3f6fa);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(true);
        web.getSettings().setAllowContentAccess(true);
        web.addJavascriptInterface(new Bridge(),"OfficeAndroid");
        // Expose JavaScript confirmations in Android's embedded WebView.
        web.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onJsConfirm(WebView view,String url,String message,JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message).setNegativeButton("Cancel",(dialog,which)->result.cancel())
                    .setPositiveButton("Confirm",(dialog,which)->result.confirm())
                    .setOnCancelListener(dialog->result.cancel()).show();
                return true;
            }
            @Override public boolean onJsAlert(WebView view,String url,String message,JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(message).setPositiveButton("OK",(dialog,which)->result.confirm())
                    .setOnCancelListener(dialog->result.confirm()).show();
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url) {refreshWeb();}
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url) {return !url.startsWith("file:///android_asset/");}
        });
        web.loadUrl("file:///android_asset/index.html");
    }
    private void refreshWeb() {if(web!=null)web.evaluateJavascript("window.officeRefresh&&window.officeRefresh()",null);}
    @Override protected void onResume(){super.onResume();if(web!=null)web.postDelayed(this::refreshWeb,200);}
    @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
    @Override protected void onDestroy(){if(web!=null){web.removeJavascriptInterface("OfficeAndroid");web.destroy();web=null;}super.onDestroy();}
    private void message(String s){runOnUiThread(()->Toast.makeText(this,s,Toast.LENGTH_LONG).show());}
    final class Bridge {
        @JavascriptInterface public String getRecords(){return VisitStore.getRecords(MainActivity.this).toString();}
        @JavascriptInterface public void setDisplaySettings(String json){VisitStore.setDisplaySettings(MainActivity.this,json);}
        @JavascriptInterface public void setRecords(String raw){try{VisitStore.setRecords(MainActivity.this,new JSONArray(raw));}catch(JSONException e){message("Could not save changes");}}
        @JavascriptInterface public String recordToday(){return VisitStore.recordToday(MainActivity.this);}
        @JavascriptInterface public void pinWidget(){runOnUiThread(()->{
            AppWidgetManager mgr=getSystemService(AppWidgetManager.class);
            ComponentName provider=new ComponentName(MainActivity.this,CheckInWidget.class);
            if(mgr!=null&&mgr.isRequestPinAppWidgetSupported())mgr.requestPinAppWidget(provider,null,null);
            else message("Long-press your Home screen → Widgets → Office Ledger");
        });}
        @JavascriptInterface public void confirmDelete(String id,String date){runOnUiThread(()->{
            new AlertDialog.Builder(MainActivity.this)
                .setTitle("Delete office record?")
                .setMessage("Permanently remove the " + date + " entry? This cannot be undone. You can export a backup before deleting.")
                .setNegativeButton("Cancel",(dialog,which)->{})
                .setPositiveButton("Delete",(dialog,which)->{
                    boolean removed=VisitStore.deleteRecord(MainActivity.this,id);
                    refreshWeb();
                    message(removed?"Office record deleted":"Record not found");
                }).show();
        });}
        @JavascriptInterface public void importJson(){runOnUiThread(()->{
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
            i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,IMPORT);
        });}
        @JavascriptInterface public void exportJson(){runOnUiThread(()->{
            Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
            i.putExtra(Intent.EXTRA_TITLE,"office-ledger-backup-"+VisitStore.today()+".json");startActivityForResult(i,EXPORT);
        });}
        @JavascriptInterface public void resetRecords(){VisitStore.resetRecords(MainActivity.this);}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try {
            if(requestCode==IMPORT) {
                ByteArrayOutputStream buf=new ByteArrayOutputStream();
                try(InputStream stream=getContentResolver().openInputStream(uri)){
                    if(stream==null)throw new IllegalStateException("Couldn't open document");
                    byte[] b=new byte[8192];int n;while((n=stream.read(b))>=0){buf.write(b,0,n);if(buf.size()>12_000_000)throw new IllegalStateException("Backup too large");}
                }
                int n=VisitStore.importBackup(this,new String(buf.toByteArray(),StandardCharsets.UTF_8));
                refreshWeb();message("Imported "+n+" records");
            } else if(requestCode==EXPORT){
                try(OutputStream stream=getContentResolver().openOutputStream(uri)){
                    if(stream==null)throw new IllegalStateException("Couldn't save backup");
                    stream.write(VisitStore.exportBackup(this).getBytes(StandardCharsets.UTF_8));
                }
                message("Backup saved");
            }
        } catch(Exception ex){message("File operation failed: "+ex.getMessage());}
    }
}
