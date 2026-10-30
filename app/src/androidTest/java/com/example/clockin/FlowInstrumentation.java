package com.example.clockin;

import android.app.*;
import android.content.*;
import android.location.Location;
import android.os.*;
import org.json.*;
import java.time.*;
import java.util.*;

/** Runs only through the test APK on the development emulator. No production test entry points. */
public final class FlowInstrumentation extends Instrumentation {
    private int passed;
    private Context c;
    private long day;
    private final List<String> results = new ArrayList<>();
    private final Map<String,Map<String,?>> backups = new HashMap<>();
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        results.add("PASS " + name); passed++;
    }
    private SharedPreferences p() { return c.getSharedPreferences("attendance_tasks_v2",0); }
    private JSONObject task(int type) throws Exception {
        JSONObject last = null;
        for (String key:p().getAll().keySet()) if(key.startsWith("task.")) {
            JSONObject j=new JSONObject(p().getString(key,"{}"));
            if(j.getInt("type")==type && (last==null || j.getLong("start")>last.getLong("start"))) last=j;
        }
        return last;
    }
    private JSONObject fresh(int type) throws Exception { return task(type); }
    private void store(JSONObject t) throws Exception { p().edit().putString("task."+t.getString("id"),t.toString()).commit(); }
    private void setup() {
        LocalDateTime now=LocalDateTime.now().withSecond(0).withNano(0);
        day=now.minusMinutes(15).toLocalDate().toEpochDay();
        WorkConfig.setTimes(c,now.minusMinutes(15).toLocalTime(),now.minusMinutes(5).toLocalTime(),
                now.plusMinutes(15).toLocalTime(),now.plusMinutes(45).toLocalTime());
        WorkConfig.setSalary(c,0,"single");
        AttendanceRules.setEnabled(c,true);
        AttendanceRules.setReminderMode(c,ReminderFlow.MODE_SILENT);
        Location l=new Location("gps"); l.setLatitude(31); l.setLongitude(121); l.setAccuracy(5);
        l.setTime(System.currentTimeMillis()); l.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        OfficeLocation.save(c,l); OfficeLocation.observed(l);
    }
    private String shell(String command) throws Exception {
        try (java.io.InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))) {
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream(); byte[] buffer=new byte[1024]; int count;
            while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
            return out.toString("UTF-8").trim();
        }
    }
    private void main(Throwing work) throws Exception {
        Throwable[] problem={null};
        runOnMainSync(()->{try{work.run();}catch(Throwable e){problem[0]=e;}});
        if(problem[0]!=null)throw new RuntimeException(problem[0]);
    }
    private interface Throwing {void run() throws Exception;}
    private void screen(boolean on, boolean unlocked) throws Exception {
        long deadline=SystemClock.uptimeMillis()+5000;
        while(SystemClock.uptimeMillis()<deadline) {
            if(c.getSystemService(PowerManager.class).isInteractive()==on && ReminderRuntime.unlocked(c)==unlocked)return;
            SystemClock.sleep(50);
        }
        throw new AssertionError("screen state timeout on="+on+" unlocked="+unlocked);
    }
    private void newTask(String mode) throws Exception {
        main(()->{ReminderFlow.cancel(c,"screen_test_reset");p().edit().clear().commit();setup();
            AttendanceRules.setReminderMode(c,mode);ReminderFlow.triggered(c,2,day);});
    }
    private void expectAttempts(int count,String message) throws Exception {
        SystemClock.sleep(150); // Test-only wait for system broadcasts, never part of application launch.
        main(()->check(fresh(2).getInt("attempts")==count,message));
    }
    private Notification card(String id) {
        for (android.service.notification.StatusBarNotification n:c.getSystemService(NotificationManager.class).getActiveNotifications())
            if(id.equals(n.getTag()))return n.getNotification();
        return null;
    }
    private Notification awaitCard(String id) throws Exception {
        for(int i=0;i<40;i++) { Notification n=card(id); if(n!=null)return n; SystemClock.sleep(50); }
        throw new AssertionError("notification card missing");
    }
    private void feedbackRegression() throws Exception {
        newTask(ReminderFlow.MODE_SILENT);
        String id=fresh(2).getString("id");
        Notification first=awaitCard(id);
        NotificationChannel silent=c.getSystemService(NotificationManager.class).getNotificationChannel(first.getChannelId());
        check(first.fullScreenIntent==null && silent.getSound()==null && !silent.shouldVibrate(),
                "silent notification has no full-screen, sound or vibration");
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        NotificationChannel reminderStatus=manager.getNotificationChannel("attendance_foreground_status_reminder_v2");
        NotificationChannel silentStatus=manager.getNotificationChannel("attendance_foreground_status_silent_v2");
        check(reminderStatus!=null && reminderStatus.getImportance()==NotificationManager.IMPORTANCE_HIGH
                        && reminderStatus.getSound()==null && reminderStatus.shouldVibrate()
                        && java.util.Arrays.equals(reminderStatus.getVibrationPattern(),new long[]{0,80}),
                "reminder foreground status uses one short channel vibration without sound");
        check(silentStatus!=null && silentStatus.getImportance()==NotificationManager.IMPORTANCE_HIGH
                        && silentStatus.getSound()==null && !silentStatus.shouldVibrate(),
                "silent foreground status channel has no sound or vibration");
        check((first.flags & Notification.FLAG_AUTO_CANCEL)==0,"notification tap preserves card for progress updates");
        main(()->{
            JSONObject t=fresh(2);t.put("requestId","synthetic-feedback").put("state",ReminderFlow.REQUESTING_OPEN)
                    .put("attempts",1).put("launch","waiting_result");store(t);
            // Synthetic observation validates consumption/feedback dispatch only, not real WeCom evidence.
            Class<?> cls=Class.forName("com.example.clockin.WeComLauncher$Request");
            java.lang.reflect.Constructor<?> ctor=cls.getDeclaredConstructors()[0];ctor.setAccessible(true);
            Object req=ctor.newInstance(c,id,"synthetic-feedback","fixture",new ClockInAccessibilityService.Foreground("fixture",1));
            java.lang.reflect.Field pending=WeComLauncher.class.getDeclaredField("pending");pending.setAccessible(true);pending.set(null,req);
            java.lang.reflect.Method finish=WeComLauncher.class.getDeclaredMethod("finish",cls,boolean.class,String.class);finish.setAccessible(true);
            finish.invoke(null,req,true,"synthetic integration observation");
            check(!WeComLauncher.busy(),"observed request consumed before feedback");
            check(fresh(2).getBoolean("foregroundObserved") && !fresh(2).getBoolean("foregroundConfirmed"),
                    "observed foreground remains distinct from causal confirmation");
            check(ReminderFlow.RESULT_UNKNOWN.equals(fresh(2).getString("result")),"foreground feedback never confirms attendance");
            check(!c.getSharedPreferences("launch_requests",0).getBoolean("statusNotificationSubmitted",true),
                    "opened WeCom does not submit another alerting status notification");
            // A duplicate callback must not persist or vibrate again.
            c.getSharedPreferences("launch_requests",0).edit().putString("outcome","duplicate-sentinel").commit();
            finish.invoke(null,req,true,"duplicate synthetic observation");
            check("duplicate-sentinel".equals(c.getSharedPreferences("launch_requests",0).getString("outcome","")),
                    "duplicate callback cannot execute feedback branch twice");
        });
        SystemClock.sleep(200);
        Notification observed=awaitCard(id);
        check(observed.extras.getString(Notification.EXTRA_TEXT).contains("已观察到企业微信进入前台"),
                "same task card updates to observed foreground and pending attendance");
        check(observed.fullScreenIntent==null,"foreground update never presents full-screen");
        main(()->{
            ReminderFlow.dismissed(c,id);c.getSystemService(NotificationManager.class).cancel(id,fresh(2).getInt("notificationId"));
            ReminderFlow.launchFinished(c,id,"synthetic-feedback",true);
        });
        SystemClock.sleep(150);
        check(card(id)==null,"foreground callback never recreates a swiped card");
    }
    private void screens() throws Exception {
        if(c.getSystemService(KeyguardManager.class).isDeviceSecure()) {
            results.add("SKIP screen tests: existing secure lock is not changed"); return;
        }
        String disabled=shell("locksettings get-disabled");
        try {
            shell("locksettings set-disabled false");
            shell("input keyevent 223");screen(false,false);
            long lockedBy=SystemClock.uptimeMillis()+5000;
            while(!c.getSystemService(KeyguardManager.class).isKeyguardLocked() && SystemClock.uptimeMillis()<lockedBy)
                SystemClock.sleep(50);
            check(c.getSystemService(KeyguardManager.class).isKeyguardLocked(),"keyguard active before locked reminder fixture");
            shell("appops set com.example.clockin USE_FULL_SCREEN_INTENT ignore");
            shell("appops set --uid com.example.clockin USE_FULL_SCREEN_INTENT ignore");
            newTask(ReminderFlow.MODE_REMINDER);
            Notification fallback=awaitCard(fresh(2).getString("id"));
            check(fallback.fullScreenIntent==null,"denied full-screen permission keeps ordinary notification");
            check(!ReminderFlow.canUseFullScreen(c),"settings reports actual denied full-screen permission");
            shell("appops set com.example.clockin USE_FULL_SCREEN_INTENT allow");
            shell("appops set --uid com.example.clockin USE_FULL_SCREEN_INTENT allow");
            main(()->{
                ReminderFlow.cancel(c,"alarm_fixture_reset");p().edit().clear().commit();setup();
                AttendanceRules.setReminderMode(c,ReminderFlow.MODE_REMINDER);
                java.lang.reflect.Method alarm=AlarmScheduler.class.getDeclaredMethod("scheduleAt",Context.class,
                        LocalDate.class,int.class,long.class,int.class);alarm.setAccessible(true);
                alarm.invoke(null,c,LocalDate.ofEpochDay(day),2,System.currentTimeMillis()+1000,0);
            });
            long deliveredBy=SystemClock.uptimeMillis()+6000;
            while(fresh(2)==null && SystemClock.uptimeMillis()<deliveredBy)SystemClock.sleep(50);
            check(fresh(2)!=null,"native alarm receiver creates lunch task while screen is off");
            expectAttempts(0,"screen-off reminder waits without launch");
            String lockId=fresh(2).getString("id");
            Notification lock=awaitCard(lockId);
            check(lock.fullScreenIntent!=null,"locked reminder includes authorized full-screen entry");
            check(c.getSystemService(NotificationManager.class).getNotificationChannel(lock.getChannelId()).getImportance()
                    >=NotificationManager.IMPORTANCE_HIGH,"locked reminder channel is high priority");
            main(()->check(ReminderFlow.lockReminderText(c,lockId)!=null,"locked reminder presents only a valid pending task"));
            java.lang.reflect.Field activityField=LockReminderActivity.class.getDeclaredField("current");activityField.setAccessible(true);
            long shownBy=SystemClock.uptimeMillis()+4000;
            while(((java.lang.ref.WeakReference<?>)activityField.get(null)).get()==null && SystemClock.uptimeMillis()<shownBy)
                SystemClock.sleep(50);
            main(()->{
                LockReminderActivity lockActivity=(LockReminderActivity)((java.lang.ref.WeakReference<?>)activityField.get(null)).get();
                check(lockActivity!=null && !lockActivity.isFinishing(),"system notification opens native lock reminder activity");
            });
            boolean ownReminderDrawn=false;
            long drawnBy=SystemClock.uptimeMillis()+4000;
            while(!ownReminderDrawn && SystemClock.uptimeMillis()<drawnBy) {
                android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
                if(root!=null) {
                    if(c.getPackageName().contentEquals(root.getPackageName()==null?"":root.getPackageName()))
                        ownReminderDrawn=!root.findAccessibilityNodeInfosByText("滑动解锁").isEmpty();
                    root.recycle();
                }
                if(!ownReminderDrawn)SystemClock.sleep(50);
            }
            check(ownReminderDrawn,"native lock reminder exposes the swipe unlock hint over keyguard");
            SystemClock.sleep(250); // Capture after the system window animation; test only.
            android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();
            if(shot!=null)try(java.io.FileOutputStream stream=new java.io.FileOutputStream(
                    new java.io.File(c.getExternalFilesDir(null),"locked-reminder-test.png"))) {
                shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream);shot.recycle();
            }
            main(()->{
                try {
                    LockReminderActivity activity=(LockReminderActivity)((java.lang.ref.WeakReference<?>)activityField.get(null)).get();
                    java.lang.reflect.Field rootField=LockReminderActivity.class.getDeclaredField("swipeRoot");rootField.setAccessible(true);
                    check(((LockReminderSwipeLayout)rootField.get(activity)).requestUnlock("instrumentation_right_swipe"),
                            "right-swipe completion requests system unlock through the native entry");
                } catch(Exception e) { throw new RuntimeException(e); }
            });
            SystemClock.sleep(TaskPolicy.SWIPE_EXIT_MS+100);
            JSONObject responded=fresh(2);
            check(!responded.getBoolean("lockReminderDismissed"),"unlock request does not mark the reminder dismissed");
            check(responded.getBoolean("waitingVibrationSuppressed"),"unlock request suppresses this task waiting haptics");
            String state=responded.getString("state");
            check(ReminderFlow.WAITING_UNLOCK.equals(state) || ReminderFlow.REQUESTING_OPEN.equals(state)
                            || ReminderFlow.WAITING_RESULT.equals(state) || ReminderFlow.WAITING_USER.equals(state),
                    "system unlock result remains separate from task completion");
            check(ReminderFlow.RESULT_UNVERIFIED.equals(responded.getString("result")),"unlock request does not change attendance result");
            awaitCard(lockId);
            main(()->{
                AttendanceRules.setReminderMode(c,ReminderFlow.MODE_SILENT);ReminderFlow.onModeChanged(c,ReminderFlow.MODE_SILENT);
                java.lang.reflect.Field owner=ReminderFlow.class.getDeclaredField("vibrationOwner");owner.setAccessible(true);
                check("".equals(owner.get(null)),"switch to silent clears native vibration ownership");
                check(ReminderFlow.lockReminderText(c,lockId)==null,"switch to silent closes lock reminder activity");
                AttendanceRules.setReminderMode(c,ReminderFlow.MODE_REMINDER);ReminderFlow.onModeChanged(c,ReminderFlow.MODE_REMINDER);
            });
            shell("input keyevent 224");screen(true,false);
            expectAttempts(0,"screen-on with keyguard does not launch");
            shell("wm dismiss-keyguard");screen(true,true);
            expectAttempts(1,"actual system unlock broadcast launches reminder once");
            shell("input keyevent 224");expectAttempts(1,"repeated screen-on does not relaunch");
            newTask(ReminderFlow.MODE_SILENT);
            expectAttempts(0,"silent task waits when triggered unlocked");
            shell("input keyevent 223");screen(false,false);
            shell("input keyevent 224");screen(true,false);
            expectAttempts(0,"silent bright locked screen does not launch");
            shell("wm dismiss-keyguard");screen(true,true);
            expectAttempts(1,"silent genuine unlock uses common launch gate");
            shell("locksettings set-disabled true");
            shell("input keyevent 223");screen(false,false);
            newTask(ReminderFlow.MODE_SILENT);
            shell("input keyevent 224");
            SystemClock.sleep(500);
            if(ReminderRuntime.unlocked(c)) {
                expectAttempts(1,"no-security-lock screen-on requires live unlocked state");
            } else {
                expectAttempts(0,"disabled-lock setting cannot bypass actual keyguard state");
                results.add("SKIP no-lock transition: emulator still reports Keyguard locked after locksettings disable");
            }
        } finally {
            shell("appops set com.example.clockin USE_FULL_SCREEN_INTENT allow");
            shell("appops set --uid com.example.clockin USE_FULL_SCREEN_INTENT allow");
            shell("input keyevent 224");shell("wm dismiss-keyguard");
            shell("locksettings set-disabled "+("true".equals(disabled)?"true":"false"));
        }
    }
    @Override public void onStart() {
        c=getTargetContext();
        Bundle out=new Bundle();
        Throwable[] error={null};
        int resultCode=Activity.RESULT_CANCELED;
        try {
            runOnMainSync(()->{
                try {
                    for(String name:new String[]{"attendance_tasks_v2","attendance_rules","work_config","office_location","scheduled_alarms_v3","launch_requests"}) {
                        SharedPreferences prefs=c.getSharedPreferences(name,0);
                        backups.put(name,new HashMap<>(prefs.getAll())); prefs.edit().clear().commit();
                    }
                    setup();
                    check(ReminderRuntime.unlocked(c),"test device genuinely unlocked");
                    ReminderFlow.triggered(c,2,day);
                    JSONObject t=task(2); String id=t.getString("id");
                    check(t.getInt("attempts")==0,"silent trigger does not launch while already unlocked");
                    check(t.getBoolean("notificationAttempted"),"silent notification write-ahead record");
                    int nid=t.getInt("notificationId");
                    ReminderFlow.triggered(c,2,day);
                    check(fresh(2).getInt("notificationId")==nid,"duplicate callback preserves notification identity");
                    ReminderFlow.dismissed(c,id);
                    c.getSystemService(NotificationManager.class).cancel(id,nid);
                    ReminderFlow.restore(c); ReminderFlow.triggered(c,2,day);
                    check(fresh(2).getBoolean("notificationDismissed"),"dismiss persisted across restore");
                    check(c.getSystemService(NotificationManager.class).getActiveNotifications().length==0,"dismissed notification not republished");
                    ReminderFlow.unlockedEvent(c,"instrumentation_new_unlock");
                    check(fresh(2).getInt("attempts")==1,"valid unlock enters launch gate");
                    check(ReminderFlow.RESULT_UNKNOWN.equals(fresh(2).getString("result")),"missing WeCom is unknown not failed attendance");
                    check(!c.getSharedPreferences("launch_requests",0).getBoolean("feedbackSubmitted",false),
                            "missing WeCom cannot emit foreground feedback");
                    ReminderFlow.unlockedEvent(c,"duplicate_unlock"); ReminderFlow.openAction(c,id);
                    check(fresh(2).getInt("attempts")==1,"unknown and simultaneous notification click cannot repeat launch");
                    ReminderFlow.confirmComplete(c,id);
                    ReminderFlow.triggered(c,2,day); ReminderFlow.retryOpen(c,id);
                    check(ReminderFlow.RESULT_COMPLETE.equals(fresh(2).getString("result")),"manual completion retained");
                    check("user_manual".equals(fresh(2).getString("resultSource")),"manual source recorded");
                    check(fresh(2).getInt("attempts")==1,"completed task never relaunches");
                    AttendanceRules.setReminderMode(c,ReminderFlow.MODE_REMINDER); ReminderFlow.onModeChanged(c,ReminderFlow.MODE_REMINDER);
                    AttendanceRules.setReminderMode(c,ReminderFlow.MODE_SILENT); ReminderFlow.onModeChanged(c,ReminderFlow.MODE_SILENT);
                    check(ReminderFlow.RESULT_COMPLETE.equals(fresh(2).getString("result")),"mode switch preserves completion");
                    ReminderFlow.triggered(c,1,day);
                    JSONObject arrival=task(1);
                    check(arrival!=null,"arrival honors fresh in-range position");
                    check(arrival.getInt("notificationId")!=nid,"different tasks have different notification ids");
                    // Invalidate position without changing task identity.
                    c.getSharedPreferences("office_location",0).edit().putBoolean("is_set",false).commit();
                    ReminderFlow.unlockedEvent(c,"outside_company");
                    check(fresh(1).getInt("attempts")==0,"unlock outside company cannot launch");
                    ReminderFlow.stop(c,arrival.getString("id"));
                    ReminderFlow.triggered(c,1,day);
                    check(ReminderFlow.ENDED.equals(fresh(1).getString("state")),"cancelled task stays ended");
                    // Synthetic validated results exercise policy only; this is not a real WeCom parser test.
                    t=fresh(2); t.put("state",ReminderFlow.WAITING_USER).put("result",ReminderFlow.RESULT_UNKNOWN)
                            .put("launch","waiting_user").put("requestId","synthetic-result")
                            .put("attemptedAt",System.currentTimeMillis()-61_000).put("automaticAttempts",1);
                    store(t);
                    long clocked=System.currentTimeMillis();
                    ReminderFlow.recordVerifiedResult(c,id,"synthetic-result",day-1,2,clocked,true);
                    check(ReminderFlow.RESULT_UNKNOWN.equals(fresh(2).getString("result")),"other date evidence rejected");
                    ReminderFlow.recordVerifiedResult(c,id,"synthetic-result",day,3,clocked,true);
                    check(ReminderFlow.RESULT_UNKNOWN.equals(fresh(2).getString("result")),"other shift evidence rejected");
                    ReminderFlow.recordVerifiedResult(c,id,"old-request",day,2,clocked,true);
                    check(ReminderFlow.RESULT_UNKNOWN.equals(fresh(2).getString("result")),"old request evidence rejected");
                    ReminderFlow.recordVerifiedResult(c,id,"synthetic-result",day,2,clocked,false);
                    int attempts=fresh(2).getInt("attempts");
                    AttendanceRules.setReminderMode(c,ReminderFlow.MODE_REMINDER);
                    ReminderFlow.triggered(c,2,day);
                    check(fresh(2).getInt("attempts")==attempts,"explicit incomplete cannot retry on duplicate trigger");
                    ReminderFlow.unlockedEvent(c,"new_unlock_after_incomplete");
                    check(fresh(2).getInt("attempts")==attempts+1,"new unlock after cooldown retries explicit incomplete");
                    t=fresh(2);
                    ReminderFlow.recordVerifiedResult(c,id,t.getString("requestId"),day,2,clocked,false);
                    ReminderFlow.unlockedEvent(c,"unlock_during_cooldown");
                    check(fresh(2).getInt("attempts")==attempts+1,"incomplete cooldown enforced in full flow");
                    t=fresh(2); t.put("attemptedAt",System.currentTimeMillis()-61_000);store(t);
                    ReminderFlow.unlockedEvent(c,"third_attempt");
                    check(fresh(2).getInt("automaticAttempts")==3,"third automatic attempt allowed");
                    t=fresh(2);
                    ReminderFlow.recordVerifiedResult(c,id,t.getString("requestId"),day,2,clocked,false);
                    t=fresh(2); t.put("attemptedAt",System.currentTimeMillis()-61_000);store(t);
                    ReminderFlow.unlockedEvent(c,"fourth_attempt");
                    check(fresh(2).getInt("automaticAttempts")==3,"fourth automatic attempt blocked");
                    ReminderFlow.confirmComplete(c,id);
                    check(ReminderFlow.RESULT_INCOMPLETE.equals(fresh(2).getString("automaticResult"))
                            && fresh(2).getLong("manualConfirmedAt")>0,"automatic and manual result history kept separately");
                    // Persist an interrupted request to exercise process-recovery semantics.
                    t.put("state",ReminderFlow.REQUESTING_OPEN).put("result",ReminderFlow.RESULT_UNVERIFIED)
                            .put("launch","waiting_result").put("requestId","dead-process-request"); store(t);
                    ReminderFlow.restore(c);
                    check(ReminderFlow.RESULT_UNKNOWN.equals(fresh(2).getString("result")),"in-flight rebuild yields unknown without retry");
                    check("waiting_user".equals(fresh(2).getString("launch")),"old in-flight launch no longer blocks manual recovery");
                    t=fresh(2); t.put("end",System.currentTimeMillis()-1); store(t);
                    ReminderFlow.restore(c);
                    check(ReminderFlow.EXPIRED.equals(fresh(2).getString("state")),"expired records not restored");
                    AttendanceRules.setEnabled(c,false); ReminderFlow.cancel(c,"test_pause");
                    check(c.getSystemService(NotificationManager.class).getActiveNotifications().length==0,"pause removes all task notifications");
                } catch(Throwable e) {error[0]=e;}
            });
            if(error[0]!=null) throw new RuntimeException(error[0]);
            feedbackRegression();
            screens();
            out.putString("stream",String.join("\n",results)+"\nPASS "+passed+" integration assertions\n");
            resultCode=Activity.RESULT_OK;
        } catch(Throwable e) {
            out.putString("stream",String.join("\n",results)+"\nFAIL "+android.util.Log.getStackTraceString(e));
            resultCode=Activity.RESULT_CANCELED;
        } finally {
            runOnMainSync(()->{
                ReminderFlow.cancel(c,"test_cleanup");
                for(Map.Entry<String,Map<String,?>> b:backups.entrySet()) {
                    SharedPreferences.Editor edit=c.getSharedPreferences(b.getKey(),0).edit().clear();
                    for(Map.Entry<String,?> e:b.getValue().entrySet()) {
                        Object v=e.getValue(); String k=e.getKey();
                        if(v instanceof String)edit.putString(k,(String)v);
                        else if(v instanceof Boolean)edit.putBoolean(k,(Boolean)v);
                        else if(v instanceof Long)edit.putLong(k,(Long)v);
                        else if(v instanceof Integer)edit.putInt(k,(Integer)v);
                        else if(v instanceof Float)edit.putFloat(k,(Float)v);
                    }
                    edit.commit();
                }
            });
        }
        finish(resultCode,out);
    }
}
