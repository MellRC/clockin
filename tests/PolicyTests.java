package com.example.clockin;
import java.time.*;
public final class PolicyTests {
    private static int n;
    private static void check(boolean ok, String name) { if (!ok) throw new AssertionError(name); n++; }
    public static void main(String[] args) {
        ZoneId z = ZoneId.of("Asia/Shanghai");
        LocalDate d = LocalDate.of(2026,10,15);
        long[] a = TaskPolicy.window(d,1,LocalTime.of(23,30),LocalTime.of(23,40),LocalTime.of(23,45),LocalTime.of(23,50),z);
        check(Instant.ofEpochMilli(a[1]).atZone(z).toLocalDate().equals(d.plusDays(1)), "arrival crosses midnight");
        check(TaskPolicy.within(a[0],a[0],a[1]), "start included");
        check(!TaskPolicy.within(a[1],a[0],a[1]), "end excluded");
        check(!TaskPolicy.within(a[0]-1,a[0],a[1]), "before excluded");
        check(TaskPolicy.within(a[1]-1,a[0],a[1]), "last ms included");
        check(a[1]-a[0]==3_600_000, "existing sixty minute arrival rule");
        long[] lunch=TaskPolicy.window(d,2,LocalTime.of(22,0),LocalTime.of(23,30),LocalTime.of(0,15),LocalTime.of(6,0),z);
        check(lunch[1]>lunch[0],"overnight lunch ordered");
        check(Instant.ofEpochMilli(lunch[1]).atZone(z).toLocalDate().equals(d.plusDays(1)),"lunch end workday ownership");
        long[] back=TaskPolicy.window(d,3,LocalTime.of(22,0),LocalTime.of(23,30),LocalTime.of(0,15),LocalTime.of(6,0),z);
        check(back[0]==lunch[1],"adjacent half-open windows");
        check(back[1]-back[0]==3_600_000,"lunch return lasts sixty minutes");
        check(!TaskPolicy.within(back[0],lunch[0],lunch[1]),"next shift cannot match prior shift");
        long[] evening=TaskPolicy.window(d,4,LocalTime.of(7,30),LocalTime.of(12,30),LocalTime.of(13,15),LocalTime.of(18,0),z);
        check(evening[1]-evening[0]==3_600_000,"evening window lasts sixty minutes");
        check(Instant.ofEpochMilli(evening[1]).atZone(z).toLocalTime().equals(LocalTime.of(19,0)),"evening ends one hour after work");
        check(!TaskPolicy.canRetry(59999,0,1,2,1),"cooldown blocks");
        check(TaskPolicy.canRetry(60000,0,1,2,1),"sixty second boundary");
        check(!TaskPolicy.canRetry(90000,0,3,4,3),"automatic maximum three");
        check(!TaskPolicy.canRetry(90000,0,1,1,1),"same unlock cannot retry");
        check(!TaskPolicy.canRetry(90000,0,1,0,1),"old unlock cannot retry");
        check(TaskPolicy.freshLocation(181000,1000),"location age boundary");
        check(!TaskPolicy.freshLocation(181001,1000),"stale position rejected");
        check(!TaskPolicy.freshLocation(1,2),"future position rejected");
        check(!TaskPolicy.freshLocation(1,0),"missing timestamp rejected");
        check(LaunchEvidence.matches(100,200,190,true,1,2,"A","A"),"matching transition evidence");
        check(!LaunchEvidence.matches(100,200,99,true,1,2,"A","A"),"queued older event rejected");
        check(!LaunchEvidence.matches(100,200,100,true,1,2,"A","A"),"same timestamp ambiguous");
        check(!LaunchEvidence.matches(100,6000,5900,true,1,2,"A","A"),"late event rejected");
        check(!LaunchEvidence.matches(100,200,190,false,1,2,"A","A"),"unknown or already foreground rejected");
        check(!LaunchEvidence.matches(100,200,190,true,1,1,"A","A"),"same window rejected");
        check(!LaunchEvidence.matches(100,200,190,true,1,2,"A","B"),"unrelated activity rejected");
        check(!LaunchEvidence.matches(100,200,300,true,1,2,"A","A"),"future event rejected");
        long[] waveform=TaskPolicy.waitingPattern(8050);
        check(java.util.Arrays.stream(waveform).sum()==8050,"waveform ends exactly at deadline");
        check(waveform[1]==350 && waveform[2]==1650 && waveform[3]==350 && waveform[4]==1650
                && waveform[5]==350 && waveform[6]==1650 && waveform[7]==350 && waveform[8]==1650
                && waveform[9]==50,
                "vibration uses bounded 350ms pulses with 1650ms gaps");
        check(java.util.Arrays.stream(TaskPolicy.waitingPattern(1)).sum()==1,"last millisecond bounded vibration");
        check(java.util.Arrays.stream(TaskPolicy.waitingPattern(Long.MAX_VALUE)).sum()==TaskPolicy.VIBRATION_SEGMENT_MS,
                "vibration segment is capped to 8 minutes");
        check(!TaskPolicy.unlocked(false,false,false),"screen-off cannot be unlocked");
        check(!TaskPolicy.unlocked(true,true,false),"bright keyguard is not unlocked");
        check(!TaskPolicy.unlocked(true,false,true),"device credential lock still blocks");
        check(TaskPolicy.unlocked(true,false,false),"no-security-lock genuine awake state accepted");
        check(TaskPolicy.shouldCollapseSwipe(360,40,200,1000,1,10,false,false),"slow right swipe reaches 35 percent width threshold");
        check(TaskPolicy.shouldCollapseSwipe(60,5,1100,1000,1,10,false,false),"fast right swipe reaches distance and velocity thresholds");
        check(!TaskPolicy.shouldCollapseSwipe(47,5,1100,1000,1,10,false,false),"fast swipe below 48dp does not collapse");
        check(!TaskPolicy.shouldCollapseSwipe(400,300,1400,1000,1,10,false,false),"diagonal swipe does not collapse");
        check(!TaskPolicy.shouldCollapseSwipe(400,0,1400,1000,1,10,true,false),"multitouch swipe does not collapse");
        check(!TaskPolicy.shouldCollapseSwipe(400,0,1400,1000,1,10,false,true),"cancelled swipe does not collapse");
        System.out.println("PASS " + n + " policy assertions");
    }
}
