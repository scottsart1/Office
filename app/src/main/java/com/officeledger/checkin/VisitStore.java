package com.officeledger.checkin;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** The single offline store shared by the app UI and its home-screen widget. */
public final class VisitStore {
    private static final String PREF = "office_ledger_private_v1";
    private static final String RECORDS = "records";
    private static final String LAST = "lastCheckIn";
    private static final SimpleDateFormat DATE = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm", Locale.US);
    private static final SimpleDateFormat PRETTY = new SimpleDateFormat("EEE, MMM d · h:mm a", Locale.US);
    private static final Set<String> EXCLUSIONS = new HashSet<>();
    static {
        for (String s : new String[] {
             "2025-11-11", "2026-01-19", "2026-02-16", "2026-05-25", "2026-06-19",
             "2026-09-07", "2026-10-12", "2026-11-11", "2026-05-18", "2026-05-19", "2026-05-20", "2026-05-21",
             "2026-06-29", "2026-06-30", "2026-07-01", "2026-07-02", "2026-07-03"}) EXCLUSIONS.add(s);
        for (int i=24;i<=28;i++) EXCLUSIONS.add("2025-11-"+i);
        for (int i=22;i<=31;i++) EXCLUSIONS.add("2025-12-"+i);
        EXCLUSIONS.add("2026-01-01"); EXCLUSIONS.add("2026-01-02");
    }

    private VisitStore() { }
    private static SharedPreferences pref(Context c) { return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE); }
    public static synchronized JSONArray getRecords(Context c) {
        String stored=pref(c).getString(RECORDS,null);
        if (stored!=null) try {return new JSONArray(stored);} catch(JSONException ignored) { }
        try (InputStream stream=c.getAssets().open("seed.json")) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] b=new byte[8192];int n;while((n=stream.read(b))!=-1)out.write(b,0,n);
            String seed=new String(out.toByteArray(),StandardCharsets.UTF_8);
            JSONArray records=new JSONArray(seed);
            pref(c).edit().putString(RECORDS, records.toString()).apply();
            return records;
        } catch (Exception ignored) {return new JSONArray();}
    }
    public static synchronized void setRecords(Context c,JSONArray arr) {pref(c).edit().putString(RECORDS,arr.toString()).commit();CheckInWidget.refreshAll(c);}
    public static synchronized void resetRecords(Context c) {pref(c).edit().remove(RECORDS).commit();getRecords(c);CheckInWidget.refreshAll(c);}
    public static String today() {synchronized(DATE) {return DATE.format(Calendar.getInstance().getTime());}}
    private static boolean weekday(Calendar c) {int d=c.get(Calendar.DAY_OF_WEEK);return d!=Calendar.SATURDAY&&d!=Calendar.SUNDAY;}
    public static boolean eligible(Calendar c) {return weekday(c)&&!EXCLUSIONS.contains(fmtDate(c));}
    private static String fmtDate(Calendar c) {synchronized(DATE){return DATE.format(c.getTime());}}
    public static synchronized String recordToday(Context context) {
        Calendar now=Calendar.getInstance();String day=fmtDate(now);JSONArray arr=getRecords(context);
        for(int i=0;i<arr.length();i++) {
            JSONObject o=arr.optJSONObject(i);if(o==null)continue;
            if(day.equals(o.optString("date"))&&o.optInt("credits",0)>0) {
                pref(context).edit().putString(LAST,"Already recorded: "+day).apply();CheckInWidget.refreshAll(context);return "existing";
            }
            if(day.equals(o.optString("date"))&&o.optString("id").startsWith("widget-"))return "existing";
        }
        boolean valid=eligible(now);
        JSONObject row=new JSONObject();
        try {
            row.put("id","widget-"+day);
            row.put("date",day);row.put("office","McLean HQ");
            synchronized(TIME){row.put("arrival",TIME.format(now.getTime()));}
            row.put("departure","");row.put("duration",0);row.put("credits",valid?1:0);
            row.put("source","Home-screen widget (manual)");
            row.put("evidence","");row.put("review",true);
            row.put("notes",valid?"Manual check-in only; location and official qualification not independently verified.":"Manual check-in on a weekend or policy-excluded date; recorded but not counted.");
            arr.put(row);pref(context).edit().putString(RECORDS,arr.toString()).putString(LAST,(valid?"Recorded ":"Saved, not eligible: ")+PRETTY.format(now.getTime())).commit();
            CheckInWidget.refreshAll(context);
        } catch(JSONException ignored) {return "error";}
        return valid?"added":"weekend";
    }
    public static String lastStatus(Context c) {return pref(c).getString(LAST,"Tap to record a manual check-in");}
    public static synchronized int rolling13Weeks(Context ctx) {
        Calendar end=Calendar.getInstance();Calendar start=(Calendar)end.clone();start.add(Calendar.DATE,-90);
        String s=fmtDate(start),e=fmtDate(end);Set<String> unique=new HashSet<>();JSONArray arr=getRecords(ctx);
        for(int i=0;i<arr.length();i++){
            JSONObject r=arr.optJSONObject(i);if(r==null||r.optInt("credits",0)<1)continue;
            String d=r.optString("date");if(d.compareTo(s)<0||d.compareTo(e)>0||EXCLUSIONS.contains(d))continue;
            try {Calendar c=Calendar.getInstance();synchronized(DATE){c.setTime(DATE.parse(d));}if(weekday(c))unique.add(d);}catch(Exception ignored){}
        }
        return unique.size();
    }
    /** Import old HTML backup JSON; preserve widget check-ins not already represented by the import. */
    public static synchronized int importBackup(Context c,String json) throws JSONException {
        Object parsed=new org.json.JSONTokener(json).nextValue();
        JSONArray incoming=parsed instanceof JSONArray?(JSONArray)parsed:((JSONObject)parsed).optJSONArray("records");
        if(incoming==null)throw new JSONException("No records array in backup");
        JSONArray next=new JSONArray();Set<String> ids=new HashSet<>();Set<String> occupied=new HashSet<>();
        for(int i=0;i<incoming.length();i++){
            JSONObject o=incoming.optJSONObject(i);if(o==null||!o.optString("date").matches("\\d{4}-\\d{2}-\\d{2}"))continue;
            next.put(o);ids.add(o.optString("id"));if(o.optInt("credits",0)>0)occupied.add(o.optString("date"));
        }
        if(next.length()==0&&incoming.length()>0)throw new JSONException("No valid records");
        JSONArray old=getRecords(c);
        for(int i=0;i<old.length();i++){
            JSONObject o=old.optJSONObject(i);if(o==null||!o.optString("id").startsWith("widget-"))continue;
            if(!ids.contains(o.optString("id"))&&!occupied.contains(o.optString("date")))next.put(o);
        }
        setRecords(c,next);return next.length();
    }
    public static String exportBackup(Context c) throws JSONException {
        JSONObject out=new JSONObject();out.put("app","Office Ledger");out.put("version",5);out.put("records",getRecords(c));out.put("exportedAt",System.currentTimeMillis());return out.toString(2);
    }
}
