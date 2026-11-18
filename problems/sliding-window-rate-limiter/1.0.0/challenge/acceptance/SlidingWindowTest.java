package limiter;

import static org.junit.jupiter.api.Assertions.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SlidingWindowTest {
    static class Time extends Clock {
        long now;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId z) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }
    final Time clock = new Time();
    final QuotaKey key = new QuotaKey("team", "search");
    final SlidingWindow limit = new SlidingWindow(clock, 10, new Rule(5, 0));
    boolean take(long n) { return limit.acquire(key, n, false); }
    @Test void admitsFirstDebit() { assertTrue(take(5)); }
    @Test void denialDoesNotConsume() { assertTrue(take(2)); assertFalse(take(4)); assertTrue(take(3)); }
    @Test void exactBoundaryExpires() { for(int i=0;i<100;i++) limit.acquire(new QuotaKey("old"+i,"r"),1,false); assertTrue(take(5)); clock.now=9; assertFalse(take(1)); clock.now=10; assertTrue(take(5)); assertEquals(1,limit.retainedKeys()); }
    @Test void expiresDebitsSeparately() { take(2); clock.now=6; take(3); clock.now=10; assertTrue(take(2)); assertFalse(take(1)); }
    @Test void keysAreTuples() { take(5); assertTrue(limit.acquire(new QuotaKey("other", "search"),5,false)); assertTrue(limit.acquire(new QuotaKey("team", "other"),5,false)); assertFalse(limit.acquire(new QuotaKey("team", "search"),1,false)); }
    @Test void validatesArguments() { assertThrows(IllegalArgumentException.class, () -> new Rule(1,2)); assertThrows(IllegalArgumentException.class, () -> take(0)); assertThrows(IllegalArgumentException.class, () -> new QuotaKey(" ","r")); assertThrows(IllegalArgumentException.class, () -> new SlidingWindow(clock,0,new Rule(1,0))); }
    @Test void arithmeticDoesNotWrap() { var s=new SlidingWindow(clock,10,new Rule(Long.MAX_VALUE,0)); clock.now=Long.MIN_VALUE; assertTrue(s.acquire(key,Long.MAX_VALUE,false)); assertFalse(s.acquire(key,1,false)); clock.now=Long.MAX_VALUE; assertTrue(s.acquire(key,Long.MAX_VALUE,false)); }
    @Test void rollbackCannotShortenWindow() { clock.now=100; take(1); clock.now=0; take(4); clock.now=109; assertFalse(take(1)); }
    int parallel(boolean priority) throws Exception {
        var gate=new CountDownLatch(1); var successes=new AtomicInteger();
        try (var pool=Executors.newFixedThreadPool(8)) {
            var jobs=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<32;i++) jobs.add(pool.submit(() -> { gate.await(); if(limit.acquire(key,1,priority)) successes.incrementAndGet(); return null; }));
            gate.countDown(); for(var job:jobs) job.get(5,TimeUnit.SECONDS);
        }
        return successes.get();
    }
    @Test void updateRetainsDebits() { take(4); limit.update(new Rule(3,0)); assertFalse(take(1)); limit.update(new Rule(6,0)); assertTrue(take(2)); assertFalse(take(1)); }
    @Test void protectedReserve() { limit.update(new Rule(5,2)); assertTrue(take(3)); assertFalse(take(1)); assertTrue(limit.acquire(key,2,true)); assertFalse(limit.acquire(key,1,true)); }
    @Test void reserveNeverOversold() throws Exception { limit.update(new Rule(5,2)); assertEquals(3,parallel(false)); assertEquals(2,parallel(true)); }
    @Test void priorityFirstConsumesSharedCapacity() {
        limit.update(new Rule(5,2)); assertTrue(limit.acquire(key,4,true)); assertFalse(take(1));
    }
    @Test void concurrentUpdateHasALegalLinearization() throws Exception {
        try (var pool = Executors.newFixedThreadPool(3)) {
            for (int round=0; round<64; round++) {
                var s = new SlidingWindow(clock,10,new Rule(4,0));
                var barrier = new CyclicBarrier(3); var sequence = new AtomicInteger();
                int[] begin = new int[3], end = new int[3]; boolean[] result = new boolean[3];
                var jobs = new java.util.ArrayList<Future<?>>();
                for (int i=0; i<3; i++) { final int op=i; jobs.add(pool.submit(() -> {
                    barrier.await(); begin[op]=sequence.incrementAndGet();
                    if (op==0) s.update(new Rule(3,2)); else result[op]=s.acquire(key,2,op==2);
                    end[op]=sequence.incrementAndGet(); return null;
                })); }
                for (var job:jobs) job.get(5,TimeUnit.SECONDS);
                boolean legal=false;
                for (int a=0;a<3;a++) for (int b=0;b<3;b++) if(a!=b) {
                    int[] order={a,b,3-a-b}; int used=0; boolean changed=false, ok=true;
                    for(int i=0;i<3;i++) {
                        int op=order[i];
                        for(int j=i+1;j<3;j++) if(end[order[j]]<begin[op]) ok=false;
                        if(op==0) changed=true; else {
                            boolean admit=used+2 <= (changed ? (op==1 ? 1 : 3) : 4);
                            if(admit!=result[op]) ok=false;
                            if(admit) used+=2;
                        }
                    }
                    if(ok) legal=true;
                }
                assertTrue(legal); assertFalse(s.acquire(key,4,true));
            }
        }
    }
    @Test void concurrentQuota() throws Exception { assertEquals(5,parallel(false)); }
}
