package com.officeledger.checkin;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** One-tap home-screen action: a BroadcastReceiver, not a shortcut that opens the app. */
public class CheckInWidget extends AppWidgetProvider {
    public static final String MARK="com.officeledger.checkin.RECORD_TODAY";
    @Override public void onReceive(Context context,Intent intent) {
        super.onReceive(context,intent);
        if(MARK.equals(intent.getAction()))VisitStore.recordToday(context);
    }
    @Override public void onUpdate(Context context,AppWidgetManager mgr,int[] widgetIds) {
        for(int id:widgetIds)render(context,mgr,id);
    }
    private static void render(Context ctx,AppWidgetManager mgr,int id) {
        RemoteViews views=new RemoteViews(ctx.getPackageName(),R.layout.widget_office);
        VisitStore.WidgetSummary summary=VisitStore.widgetSummary(ctx);
        views.setTextViewText(R.id.widgetCount,summary.count+" / 20");
        views.setTextViewText(R.id.widgetSubtitle,summary.subtitle);
        views.setTextViewText(R.id.widgetLast,VisitStore.lastStatus(ctx));
        Intent action=new Intent(ctx,CheckInWidget.class);action.setAction(MARK);
        PendingIntent pi=PendingIntent.getBroadcast(ctx,42,action,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widgetButton,pi);
        Intent open=new Intent(ctx,MainActivity.class);open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi=PendingIntent.getActivity(ctx,43,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widgetTitle,openPi);
        views.setOnClickPendingIntent(R.id.widgetCount,openPi);
        mgr.updateAppWidget(id,views);
    }
    public static void refreshAll(Context c) {
        AppWidgetManager mgr=AppWidgetManager.getInstance(c);
        int[] ids=mgr.getAppWidgetIds(new ComponentName(c,CheckInWidget.class));
        for(int id:ids)render(c,mgr,id);
    }
}
