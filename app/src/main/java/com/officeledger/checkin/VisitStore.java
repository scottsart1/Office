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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** The single offline store shared by the app UI and its home-screen widget. */
public final class VisitStore {
    private static final String PREF = "office_ledger_private_v1";
    private static final String RECORDS = "records";
    private static final String LAST = "lastCheckIn";
    private static final String VIEW = "policyWidgetView";
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
    public static synchronized boolean deleteRecord(Context c,String id) {
        if (id==null || id.isEmpty()) return false;
        JSONArray current=getRecords(c),updated=new JSONArray();
        boolean removed=false;
        for (int i=0;i<current.length();i++) {
            JSONObject row=current.optJSONObject(i);
            if (row==null) continue;
            if (!removed && id.equals(row.optString("id"))) {removed=true;continue;}
            updated.put(row);
        }
        if (removed) setRecords(c,updated);
        return removed;
    }
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

    /**
     * The app's selected policy view is shared with the home-screen widget.
     * The as-of date follows the real calendar only if the user selected Today.
     */
    public static synchronized void setDisplaySettings(Context context,String raw) {
        try {
            JSONObject input=new JSONObject(raw),view=new JSONObject();
            String mode=input.optString("mode","official");
            view.put("mode","rolling".equals(mode)?"rolling":"official");
            view.put("cycle",input.optString("cycle","2026-12-02"));
            String asof=input.optString("asof",today());
            try {LocalDate.parse(asof);} catch(Exception e) {asof=today();}
            view.put("asof",asof);
            view.put("followToday",asof.equals(today()));
            view.put("threshold",Math.max(0,input.optInt("threshold",0)));
            view.put("chicago",input.optBoolean("chicago",true));
            String next=view.toString(),old=pref(context).getString(VIEW,null);
            if(!next.equals(old)) {
                pref(context).edit().putString(VIEW,next).commit();
                CheckInWidget.refreshAll(context);
            }
        } catch(Exception ignored) { }
    }

    private static String[] cycleDates(String key) {
        switch(key) {
            case "2026-01-28":return new String[]{"2025-10-20","2026-01-16"};
            case "2026-02-25":return new String[]{"2025-11-17","2026-02-13"};
            case "2026-03-25":return new String[]{"2025-12-15","2026-03-13"};
            case "2026-04-29":return new String[]{"2026-01-19","2026-04-17"};
            case "2026-05-27":return new String[]{"2026-02-16","2026-05-15"};
            case "2026-06-24":return new String[]{"2026-03-16","2026-06-12"};
            case "2026-07-29":return new String[]{"2026-04-20","2026-07-17"};
            case "2026-08-26":return new String[]{"2026-05-18","2026-08-14"};
            case "2026-09-30":return new String[]{"2026-06-22","2026-09-18"};
            case "2026-10-28":return new String[]{"2026-07-20","2026-10-16"};
            default:return new String[]{"2026-08-17","2026-11-13"};
        }
    }
    public static final class WidgetSummary {
        public final int count;
        public final String subtitle;
        public WidgetSummary(int count,String subtitle) {
            this.count=count;this.subtitle=subtitle;
        }
    }
    private static String shortDate(LocalDate day) {
        return day.getMonth().toString().substring(0,3).charAt(0)+
               day.getMonth().toString().substring(1,3).toLowerCase(Locale.US)+
               " "+day.getDayOfMonth();
    }
    /**
     * Parity with the HTML uniqueDays(): combine records per date/office,
     * apply the McLean-only minimum-duration comparison, then deduplicate dates.
     */
    public static synchronized WidgetSummary widgetSummary(Context context) {
        JSONObject view;
        try {view=new JSONObject(pref(context).getString(VIEW,"{}"));}
        catch(Exception e) {view=new JSONObject();}
        String mode=view.optString("mode","official");
        boolean chicago=view.optBoolean("chicago",true);
        int threshold=Math.max(0,view.optInt("threshold",0));
        String date=view.optBoolean("followToday",true)?today():view.optString("asof",today());
        LocalDate through;
        try {through=LocalDate.parse(date);}
        catch(Exception e) {through=LocalDate.now();}

        LocalDate start,end;
        if("rolling".equals(mode)) {start=through.minusDays(90);end=through;}
        else {
            String[] range=cycleDates(view.optString("cycle","2026-12-02"));
            start=LocalDate.parse(range[0]);end=LocalDate.parse(range[1]);
        }
        LocalDate stop=through.isBefore(end)?through:end;
        Map<String,Double> officeDurations=new HashMap<>();
        JSONArray arr=getRecords(context);
        for(int i=0;i<arr.length();i++) {
            JSONObject row=arr.optJSONObject(i);
            if(row==null||row.optDouble("credits",0)<=0)continue;
            String ds=row.optString("date","");
            LocalDate day;
            try {day=LocalDate.parse(ds);}catch(DateTimeParseException e){continue;}
            if(day.isBefore(start)||day.isAfter(stop)||day.getDayOfWeek().getValue()>5||EXCLUSIONS.contains(ds))continue;
            String office=row.optString("office","McLean HQ");
            String key=ds+"|"+office;
            officeDurations.put(key,officeDurations.getOrDefault(key,0d)+Math.max(0,row.optDouble("duration",0)));
        }
        Set<String> unique=new HashSet<>();
        for(Map.Entry<String,Double> entry:officeDurations.entrySet()) {
            String key=entry.getKey();
            String ds=key.substring(0,10),office=key.substring(11);
            if("Chicago".equals(office)&&!chicago)continue;
            if("McLean HQ".equals(office)&&entry.getValue()<threshold)continue;
            unique.add(ds);
        }
        String subtitle="rolling".equals(mode)?
            "Rolling 13 weeks · through "+shortDate(through):
            "Official review · "+shortDate(start)+"–"+shortDate(end);
        if(threshold>0)subtitle+=" · "+threshold+"m+";
        if(!chicago)subtitle+=" · Chicago off";
        return new WidgetSummary(unique.size(),subtitle);
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
